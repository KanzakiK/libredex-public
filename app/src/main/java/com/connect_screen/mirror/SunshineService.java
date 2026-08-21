package com.connect_screen.mirror;

import static android.app.Activity.RESULT_OK;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.display.DisplayManager;
import android.media.MediaCodecInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.wifi.WifiManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import com.connect_screen.mirror.BuildConfig;
import com.connect_screen.mirror.job.SunshineServer;
import com.connect_screen.mirror.shizuku.PermissionManager;
import com.connect_screen.mirror.shizuku.ShizukuUtils;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Date;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.jmdns.JmDNS;
import javax.jmdns.ServiceInfo;

public class SunshineService extends Service {
    public static SunshineService instance;

    public enum LifecycleState {
        STOPPED,
        STARTING,
        RUNNING,
        STOPPING
    }

    private static volatile LifecycleState lifecycleState = LifecycleState.STOPPED;
    private static final AtomicBoolean startupInitInFlight = new AtomicBoolean(false);
    private static volatile boolean stopRequested = false;
    private static final AtomicInteger serviceGeneration = new AtomicInteger(0);
    private static volatile Thread activeNativeThread;
    private static final String CHANNEL_ID = "SunshineServiceChannelV2";
    private static final int NOTIFICATION_ID = 2;
    private static final String TAG = "SunshineService";
    private static final String CERT_FILE_NAME = "cacert.pem";
    private static final String KEY_FILE_NAME = "cakey.pem";
    private Thread nativeThread;
    private int instanceGeneration;

    private int currentTimeout;
    private PowerManager.WakeLock cpuWakeLock;
    private WifiManager.WifiLock wifiLock;

    public static LifecycleState getLifecycleState() {
        return lifecycleState;
    }

    public static boolean canRequestStart() {
        return lifecycleState == LifecycleState.STOPPED;
    }

    public static boolean canRequestStop() {
        return lifecycleState == LifecycleState.STARTING || lifecycleState == LifecycleState.RUNNING;
    }

    public static void markStarting() {
        stopRequested = false;
        setLifecycleState(LifecycleState.STARTING);
    }

    public static void markStopping() {
        stopRequested = true;
        setLifecycleState(LifecycleState.STOPPING);
    }

    public static void markStopped() {
        stopRequested = false;
        setLifecycleState(LifecycleState.STOPPED);
    }

    public static boolean isStopRequested() {
        return stopRequested;
    }

    public static boolean isNativeThreadRunning() {
        Thread thread = activeNativeThread;
        return thread != null && thread.isAlive();
    }

    private static void setLifecycleState(LifecycleState state) {
        lifecycleState = state;
        State.refreshMainActivity();
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        instanceGeneration = serviceGeneration.get();
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("");
            Log.i(TAG, "HiddenApiBypass enabled for input injection");
        } catch (Throwable t) {
            Log.w(TAG, "HiddenApiBypass init failed: " + t.getMessage());
        }
        if (!stopRequested) {
            setLifecycleState(LifecycleState.STARTING);
        }
        createNotificationChannel();
        startForeground(NOTIFICATION_ID, createNotification());
        acquireRuntimeLocks();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (instance == this) {
            instance = null;
        }
        if (instanceGeneration != serviceGeneration.get()) {
            // An old service instance is finishing after a newer start; do not
            // touch the current lifecycle, startup flag, or user service.
            releaseWakeLock();
            return;
        }
        startupInitInFlight.set(false);
        if (lifecycleState == LifecycleState.STOPPED) {
            State.refreshMainActivity();
        } else if (nativeThread != null && nativeThread.isAlive()) {
            setLifecycleState(LifecycleState.STOPPING);
        } else {
            setLifecycleState(LifecycleState.STOPPED);
        }
        releaseWakeLock();
        State.unbindUserService();
    }

    public void releaseWakeLock() {
        releaseRuntimeLocks();
        if (currentTimeout > 0) {
            Settings.System.putInt(this.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, currentTimeout);
            currentTimeout = 0;
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (nativeThread != null && nativeThread.isAlive()) {
            State.log("SunshineService already running, ignore duplicate start");
            State.refreshMainActivity();
            return START_NOT_STICKY;
        }
        if (!stopRequested && startupInitInFlight.get()) {
            State.log("SunshineService startup already in flight, ignore duplicate start");
            State.refreshMainActivity();
            return START_NOT_STICKY;
        }
        State.log("SunshineService start v" + BuildConfig.VERSION_NAME
                + " sdk=" + Build.VERSION.SDK_INT
                + " device=" + Build.MANUFACTURER + " " + Build.MODEL);
        final int generation = serviceGeneration.incrementAndGet();
        stopRequested = false;
        setLifecycleState(LifecycleState.STARTING);
        if (intent != null && intent.hasExtra("data")) {
            MediaProjectionManager mediaProjectionManager = (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            Intent data = intent.getParcelableExtra("data");
            State.setMediaProjection(mediaProjectionManager.getMediaProjection(RESULT_OK, data));
            State.getMediaProjection().registerCallback(new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    super.onStop();
                    State.log("MediaProjection onStop callback");
                }
            }, null);
            State.resumeJob();
        } else {
            State.log(State.getMediaProjection() != null
                    ? "SunshineService started with existing MediaProjection for Android native audio capture"
                    : "SunshineService started without MediaProjection; Android native audio capture will be unavailable");
            State.resumeJob();
        }
        if (Pref.getPreventAutoLock()) {
            preventAutoLock();
        }

        String sunshineName = "LibreDeX-" + Build.MANUFACTURER + "-" + Build.MODEL;
        SunshineServer.setSunshineName(sunshineName);
        Set<String> ipAddresses = getAllWifiIpAddresses(this);

        startupInitInFlight.set(true);
        new Thread(() -> {
            try {
                SunshineServer.setFileStatePath(SunshineService.this.getFilesDir().getAbsolutePath() + "/sunshine_state.json");
                SunshineServer.setEncoderSettingsFromPreferences();
                applyVideoCodecPreference();
                if (!writeCertAndKey(SunshineService.this)) {
                    State.log("Sunshine certificate/private key preparation failed");
                    if (generation == serviceGeneration.get()) {
                        setLifecycleState(LifecycleState.STOPPED);
                    }
                    stopSelf(startId);
                    return;
                }
                List<JmDNS> dnsServers = new ArrayList<>();
                registerJmDns(ipAddresses, dnsServers);
                if (stopRequested || generation != serviceGeneration.get()) {
                    State.log("SunshineService startup aborted for stale/stopped generation");
                    if (generation == serviceGeneration.get()) {
                        setLifecycleState(LifecycleState.STOPPING);
                    }
                    stopSelf(startId);
                    return;
                }
                nativeThread = new Thread(() -> {
                    try {
                        if (stopRequested || generation != serviceGeneration.get()) {
                            State.log("SunshineService start aborted for stale/stopped generation");
                            SunshineServer.exitServer();
                            return;
                        }
                        setLifecycleState(LifecycleState.RUNNING);
                        SunshineServer.start();
                    } catch (Throwable e) {
                        Log.e("SunshineService", "thread quit", e);
                    } finally {
                        for (JmDNS server : dnsServers) {
                            try {
                                server.close();
                            } catch (IOException e) {
                                Log.w("SunshineService", "JmDNS close failed", e);
                            }
                        }
                        if (generation == serviceGeneration.get()) {
                            activeNativeThread = null;
                            setLifecycleState(LifecycleState.STOPPED);
                            stopSelf();
                        } else {
                            State.log("SunshineService stale native thread finished, current service untouched");
                        }
                    }
                }, "SunshineNativeThread");
                activeNativeThread = nativeThread;
                nativeThread.start();
                if (ipAddresses.isEmpty()) {
                    State.log("Cannot get Wi-Fi IP address");
                } else {
                    State.log("Published Moonlight service name: " + sunshineName);
                    for (String addr : ipAddresses) {
                        State.log("Published Moonlight IP: " + addr);
                    }
                }
            } catch (Exception e) {
                Log.e("SunshineService", "Failed to initialize network service", e);
                if (generation == serviceGeneration.get()) {
                    setLifecycleState(LifecycleState.STOPPED);
                }
                stopSelf(startId);
            } finally {
                if (generation == serviceGeneration.get()) {
                    startupInitInFlight.set(false);
                }
            }
        }).start();

        State.refreshMainActivity();
        Handler handler = new Handler();
        handler.postDelayed(() -> {
            if (ShizukuUtils.hasPermission() && !State.isUserServiceAlive()) {
                State.log("try start shizuku user service");
                State.ensureUserServiceBound();
                handler.postDelayed(() -> {
                    if (ShizukuUtils.hasPermission() && !State.isUserServiceAlive()) {
                        State.log("shizuku user service start failed, revoke and grant Shizuku permission again if needed");
                        State.ensureUserServiceBound();
                    }
                }, 15 * 1000);
            }
        }, 2000);
        return START_NOT_STICKY;
    }

    private void registerJmDns(Set<String> ipAddresses, List<JmDNS> dnsServers) {
        if (ipAddresses == null || ipAddresses.isEmpty()) {
            return;
        }
        for (String addr : ipAddresses) {
            registerJmDnsAddress(addr, dnsServers);
        }
    }

    private void registerJmDnsAddress(String addr, List<JmDNS> dnsServers) {
        try {
            JmDNS jmdns = JmDNS.create(InetAddress.getByName(addr));
            dnsServers.add(jmdns);
            ServiceInfo serviceInfo = ServiceInfo.create(
                    "_nvstream._tcp.local.",
                    getString(R.string.notify_sunshine_title),
                    47989,
                    getString(R.string.notify_sunshine_title)
            );

            jmdns.registerService(serviceInfo);
            Log.i("SunshineService", "JmDNS service registered, IP: " + addr);
        } catch (Exception e) {
            Log.e("SunshineService", "Failed to register JmDNS on IP " + addr, e);
        }
    }

    private void preventAutoLock() {
        if (!ShizukuUtils.hasPermission() || Pref.getFakeScreen()) {
            return;
        }
        if (PermissionManager.grant("android.permission.WRITE_SECURE_SETTINGS")) {
            currentTimeout = Settings.System.getInt(this.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, 0);
            Log.i("SunshineService", "Current screen off timeout: " + currentTimeout + "ms");
            if (currentTimeout >= 4 * 60 * 60 * 1000) {
                currentTimeout = 15 * 1000;
            }
            Settings.System.putInt(this.getContentResolver(),
                    Settings.System.SCREEN_OFF_TIMEOUT, 4 * 60 * 60 * 1000);
        }
    }

    private void acquireRuntimeLocks() {
        acquireCpuWakeLock();
        acquireWifiLock();
    }

    private void acquireCpuWakeLock() {
        try {
            if (cpuWakeLock != null && cpuWakeLock.isHeld()) {
                return;
            }
            PowerManager powerManager = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                State.log("Sunshine runtime keepalive: PowerManager unavailable");
                return;
            }
            cpuWakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG + ":CpuLock");
            cpuWakeLock.setReferenceCounted(false);
            cpuWakeLock.acquire();
            State.log("Sunshine runtime keepalive: PARTIAL_WAKE_LOCK acquired");
        } catch (Throwable e) {
            State.log("Sunshine runtime keepalive: failed to acquire PARTIAL_WAKE_LOCK: " + e.getMessage());
        }
    }

    private void acquireWifiLock() {
        try {
            if (wifiLock != null && wifiLock.isHeld()) {
                return;
            }
            WifiManager wifiManager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager == null) {
                State.log("Sunshine runtime keepalive: WifiManager unavailable");
                return;
            }
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, TAG + ":WifiLock");
            wifiLock.setReferenceCounted(false);
            wifiLock.acquire();
            State.log("Sunshine runtime keepalive: Wi-Fi high perf lock acquired");
        } catch (Throwable e) {
            State.log("Sunshine runtime keepalive: failed to acquire Wi-Fi lock: " + e.getMessage());
        }
    }

    private void releaseRuntimeLocks() {
        if (cpuWakeLock != null) {
            try {
                if (cpuWakeLock.isHeld()) {
                    cpuWakeLock.release();
                    State.log("Sunshine runtime keepalive: PARTIAL_WAKE_LOCK released");
                }
            } catch (Throwable e) {
                State.log("Sunshine runtime keepalive: failed to release PARTIAL_WAKE_LOCK: " + e.getMessage());
            }
            cpuWakeLock = null;
        }
        if (wifiLock != null) {
            try {
                if (wifiLock.isHeld()) {
                    wifiLock.release();
                    State.log("Sunshine runtime keepalive: Wi-Fi high perf lock released");
                }
            } catch (Throwable e) {
                State.log("Sunshine runtime keepalive: failed to release Wi-Fi lock: " + e.getMessage());
            }
            wifiLock = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notify_channel_name),
                    NotificationManager.IMPORTANCE_DEFAULT
            );
            serviceChannel.setSound(null, null);
            serviceChannel.enableVibration(false);
            serviceChannel.enableLights(false);
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }

    private Notification createNotification() {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notify_sunshine_title))
                .setContentText(getString(R.string.notify_sunshine_text))
                .setSmallIcon(R.drawable.ic_launcher_monochrome_v2)
                .build();
    }

    private boolean applyVideoCodecPreference() {
        if (Pref.getEncoderCodec() == Pref.ENCODER_CODEC_H264) {
            SunshineServer.setVideoCodec(Pref.ENCODER_CODEC_H264);
            State.log("Encoder codec preference: H.264/AVC");
            return true;
        }

        try {
            android.media.MediaCodecList codecList = new android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS);
            for (android.media.MediaCodecInfo codecInfo : codecList.getCodecInfos()) {
                if (!codecInfo.isHardwareAccelerated()) {
                    continue;
                }
                if (!codecInfo.isEncoder()) {
                    continue;
                }
                if (!isSupported(codecInfo, "video/hevc")) {
                    continue;
                }
                SunshineServer.setVideoCodec(Pref.ENCODER_CODEC_H265);
                State.log("Encoder codec preference: H.265/HEVC");
                return true;
            }
            SunshineServer.setVideoCodec(Pref.ENCODER_CODEC_H264);
            State.log("Device does not support H.265/HEVC encoding, falling back to H.264/AVC");
            return false;
        } catch (Exception e) {
            SunshineServer.setVideoCodec(Pref.ENCODER_CODEC_H264);
            State.log("Failed to probe H.265 support, falling back to H.264/AVC: " + e.getMessage());
            return false;
        }
    }

    private boolean isSupported(MediaCodecInfo codecInfo, String mime) {
        String[] types = codecInfo.getSupportedTypes();
        for (String type : types) {
            if (type.equalsIgnoreCase(mime)) {
                return true;
            }
        }
        return false;
    }

    public static Set<String> getAllWifiIpAddresses(Context context) {
        Set<String> ipAddresses = new HashSet<>();

        WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null && wifiManager.isWifiEnabled()) {
            int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
            if (ipAddress != 0) {
                byte[] bytes = new byte[4];
                bytes[0] = (byte) (ipAddress & 0xFF);
                bytes[1] = (byte) ((ipAddress >> 8) & 0xFF);
                bytes[2] = (byte) ((ipAddress >> 16) & 0xFF);
                bytes[3] = (byte) ((ipAddress >> 24) & 0xFF);

                try {
                    String ip = InetAddress.getByAddress(bytes).getHostAddress();
                    ipAddresses.add(ip);
                } catch (UnknownHostException e) {
                    Log.e(TAG, "Failed to get Wi-Fi IP address", e);
                }
            }
        }

        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface ni = networkInterfaces.nextElement();
                if (ni.isUp() && !ni.isLoopback()) {
                    List<InterfaceAddress> interfaceAddresses = ni.getInterfaceAddresses();
                    for (InterfaceAddress interfaceAddress : interfaceAddresses) {
                        if (interfaceAddress.getAddress() != null) {
                            String ip = interfaceAddress.getAddress().getHostAddress();
                            if (ip != null && ip.startsWith("192.168")) {
                                ipAddresses.add(ip);
                            }
                        }
                    }
                }
            }
        } catch (SocketException e) {
            Log.e(TAG, "Failed to get network interface IP addresses", e);
        }

        return ipAddresses;
    }

    public static boolean writeCertAndKey(Context context) {
        try {
            String certPath = context.getFilesDir().getAbsolutePath() + "/" + CERT_FILE_NAME;
            String keyPath = context.getFilesDir().getAbsolutePath() + "/" + KEY_FILE_NAME;
            File certFile = new File(certPath);
            File keyFile = new File(keyPath);
            if (!certFile.exists() || !keyFile.exists()) {
                if (!copyBundledCertAndKeyIfPresent(context, certFile, keyFile)) {
                    generateCertAndKey(certFile, keyFile);
                }
            }
            if (!certFile.exists() || certFile.length() == 0 || !keyFile.exists() || keyFile.length() == 0) {
                throw new IOException("certificate/private key file is missing or empty");
            }
            SunshineServer.setCertPath(certPath);
            SunshineServer.setPkeyPath(keyPath);
            Log.i(TAG, "certificate and private key ready: " + context.getFilesDir().getAbsolutePath());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "failed to prepare certificate and key", e);
            return false;
        }
    }

    private static boolean copyBundledCertAndKeyIfPresent(Context context, File certFile, File keyFile) {
        try (InputStream certInput = context.getAssets().open(CERT_FILE_NAME);
             FileOutputStream certOutput = context.openFileOutput(CERT_FILE_NAME, Context.MODE_PRIVATE);
             InputStream keyInput = context.getAssets().open(KEY_FILE_NAME);
             FileOutputStream keyOutput = context.openFileOutput(KEY_FILE_NAME, Context.MODE_PRIVATE)) {
            copyStream(certInput, certOutput);
            copyStream(keyInput, keyOutput);
            Log.i(TAG, "copied bundled certificate and private key from assets");
            return true;
        } catch (IOException e) {
            if (certFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                certFile.delete();
            }
            if (keyFile.exists()) {
                //noinspection ResultOfMethodCallIgnored
                keyFile.delete();
            }
            Log.i(TAG, "bundled certificate/private key not found, generating runtime pair");
            return false;
        }
    }

    private static void generateCertAndKey(File certFile, File keyFile) throws Exception {
        SecureRandom secureRandom = new SecureRandom();
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048, secureRandom);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        long now = System.currentTimeMillis();
        Date notBefore = new Date(now - TimeUnit.DAYS.toMillis(1));
        Date notAfter = new Date(now + TimeUnit.DAYS.toMillis(3650));
        X500Name subject = new X500Name("CN=LibreDeX,O=LibreDeX,OU=Sunshine Android");
        BigInteger serialNumber = new BigInteger(64, secureRandom).abs();
        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                subject,
                serialNumber,
                notBefore,
                notAfter,
                subject,
                keyPair.getPublic());
        ContentSigner signer = new JcaContentSignerBuilder("SHA256withRSA")
                .build(keyPair.getPrivate());
        X509Certificate certificate = new JcaX509CertificateConverter()
                .getCertificate(certBuilder.build(signer));

        writePemFile(certFile, "CERTIFICATE", certificate.getEncoded());
        writePemFile(keyFile, "PRIVATE KEY", keyPair.getPrivate().getEncoded());
        Log.i(TAG, "generated runtime self-signed certificate and private key");
    }

    private static void writePemFile(File file, String type, byte[] derBytes) throws IOException {
        String base64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII))
                .encodeToString(derBytes);
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(file, false), StandardCharsets.US_ASCII)) {
            writer.write("-----BEGIN " + type + "-----\n");
            writer.write(base64);
            writer.write("\n-----END " + type + "-----\n");
        }
    }

    private static void copyStream(InputStream inputStream, FileOutputStream outputStream) throws IOException {
        byte[] buffer = new byte[4096];
        int length;
        while ((length = inputStream.read(buffer)) > 0) {
            outputStream.write(buffer, 0, length);
        }
        outputStream.flush();
    }
}
