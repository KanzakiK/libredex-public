/*
 * uinput-kbd.c — LibreDeX persistent virtual keyboard helper for /dev/uinput.
 *
 * Runs as a long-lived daemon started via `su -c`. It creates a kernel-level
 * keyboard (so the system enumerates it as a real external keyboard and
 * Samsung IME enables pinyin composition), then waits for commands on stdin:
 *
 *   P <evdev code>   press  (EV_KEY value=1 + EV_SYN)
 *   R <evdev code>   release(EV_KEY value=0 + EV_SYN)
 *   D                destroy device and exit
 *
 * The uinput fd is kept open for the whole session — closing it destroys the
 * device, which would turn the keyboard back into a non-hardware one.
 *
 * Build (must be PIE, NOT -static, or ARM64 Bionic reports TLS underaligned):
 *   <ndk>/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android<api>-clang \
 *     -fPIE -pie -O2 -o uinput-kbd uinput-kbd.c
 */
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <sys/time.h>
#include <linux/uinput.h>
#include <linux/input.h>

static int dev_fd = -1;

static void emit(int type, int code, int val) {
    struct input_event ev;
    memset(&ev, 0, sizeof(ev));
    ev.type = type;
    ev.code = code;
    ev.value = val;
    gettimeofday(&ev.time, NULL);
    if (write(dev_fd, &ev, sizeof(ev)) < 0) {
        fprintf(stderr, "write event type=%d code=%d val=%d: %s\n",
                type, code, val, strerror(errno));
        fflush(stderr);
    }
}

static void send_key(int code, int val) {
    emit(EV_KEY, code, val);
    emit(EV_SYN, SYN_REPORT, 0);
}

static int create_keyboard(void) {
    if (ioctl(dev_fd, UI_SET_EVBIT, EV_KEY) < 0) return -1;
    if (ioctl(dev_fd, UI_SET_EVBIT, EV_SYN) < 0) return -1;
    ioctl(dev_fd, UI_SET_EVBIT, EV_LED);
    ioctl(dev_fd, UI_SET_EVBIT, EV_MSC);
    for (int k = 1; k <= KEY_MAX; k++) {
        ioctl(dev_fd, UI_SET_KEYBIT, k);
    }
    ioctl(dev_fd, UI_SET_LEDBIT, LED_CAPSL);
    ioctl(dev_fd, UI_SET_LEDBIT, LED_NUML);
    ioctl(dev_fd, UI_SET_LEDBIT, LED_SCROLLL);
    struct uinput_setup setup;
    memset(&setup, 0, sizeof(setup));
    snprintf(setup.name, UINPUT_MAX_NAME_SIZE, "LibreDeX Virtual Keyboard");
    setup.id.bustype = BUS_USB;
    setup.id.vendor = 0x1d6b;
    setup.id.product = 0x0001;
    setup.id.version = 1;
    if (ioctl(dev_fd, UI_DEV_SETUP, &setup) < 0) return -2;
    if (ioctl(dev_fd, UI_DEV_CREATE) < 0) return -3;
    return 0;
}

int main(void) {
    dev_fd = open("/dev/uinput", O_WRONLY | O_NONBLOCK);
    if (dev_fd < 0) {
        fprintf(stderr, "open /dev/uinput: %s\n", strerror(errno));
        fflush(stderr);
        return 1;
    }
    int rc = create_keyboard();
    if (rc < 0) {
        fprintf(stderr, "create keyboard failed rc=%d: %s\n", rc, strerror(errno));
        fflush(stderr);
        close(dev_fd);
        return 1;
    }
    printf("READY\n");
    fflush(stdout);

    char line[64];
    while (fgets(line, sizeof(line), stdin)) {
        char op = 0;
        int code = -1;
        if (strlen(line) >= 2 && (line[0] == 'P' || line[0] == 'R')) {
            op = line[0];
            char *rest = line + 1;
            while (*rest == ' ' || *rest == '\t') rest++;
            code = atoi(rest);
        }
        if (op == 'P' && code >= 0 && code <= KEY_MAX) {
            send_key(code, 1);
            printf("ACK %d\n", code);
            fflush(stdout);
        } else if (op == 'R' && code >= 0 && code <= KEY_MAX) {
            send_key(code, 0);
            printf("ACK %d\n", code);
            fflush(stdout);
        } else if (line[0] == 'D') {
            break;
        } else {
            fprintf(stderr, "bad command: %s", line);
            fflush(stderr);
        }
    }

    ioctl(dev_fd, UI_DEV_DESTROY);
    close(dev_fd);
    return 0;
}