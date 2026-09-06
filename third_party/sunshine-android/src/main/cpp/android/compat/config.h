#ifndef SUNSHINE_ANDROID_FFMPEG_CONFIG_H
#define SUNSHINE_ANDROID_FFMPEG_CONFIG_H

#define CONFIG_CBS_APV 0
#define CONFIG_CBS_AV1 0
#define CONFIG_CBS_H264 1
#define CONFIG_CBS_H265 1
#define CONFIG_CBS_H266 0
#define CONFIG_CBS_JPEG 0
#define CONFIG_CBS_LCEVC 0
#define CONFIG_CBS_MPEG2 0
#define CONFIG_CBS_VP8 0
#define CONFIG_CBS_VP9 0
#define CONFIG_LINUX_PERF 0
#define CONFIG_MACOS_KPERF 0
#define CONFIG_SAFE_BITSTREAM_READER 1
#define CONFIG_SHARED 0
#define CONFIG_SMALL 0

#if defined(__aarch64__)
  #define ARCH_AARCH64 1
  #define ARCH_ARM 0
  #define ARCH_X86 0
  #define ARCH_X86_32 0
  #define ARCH_X86_64 0
#elif defined(__arm__)
  #define ARCH_AARCH64 0
  #define ARCH_ARM 1
  #define ARCH_X86 0
  #define ARCH_X86_32 0
  #define ARCH_X86_64 0
#elif defined(__x86_64__)
  #define ARCH_AARCH64 0
  #define ARCH_ARM 0
  #define ARCH_X86 1
  #define ARCH_X86_32 0
  #define ARCH_X86_64 1
#else
  #define ARCH_AARCH64 0
  #define ARCH_ARM 0
  #define ARCH_X86 0
  #define ARCH_X86_32 0
  #define ARCH_X86_64 0
#endif

#define ARCH_IA64 0
#define ARCH_LOONGARCH 0
#define ARCH_LOONGARCH32 0
#define ARCH_LOONGARCH64 0
#define ARCH_M68K 0
#define ARCH_MIPS 0
#define ARCH_MIPS64 0
#define ARCH_PARISC 0
#define ARCH_PPC 0
#define ARCH_PPC64 0
#define ARCH_RISCV 0
#define ARCH_S390 0
#define ARCH_SPARC 0
#define ARCH_SPARC64 0
#define ARCH_TILEGX 0
#define ARCH_TILEPRO 0
#define ARCH_WASM 0

#define HAVE_ARM_CRC 0
#define HAVE_ARMV5TE 0
#define HAVE_ARMV6 0
#define HAVE_ARMV6_INLINE 0
#define HAVE_ARMV6T2 0
#define HAVE_ARMV8 0
#define HAVE_ASM_MOD_Q 0
#define HAVE_BIGENDIAN 0
#define HAVE_CLOCK_GETTIME 1
#define HAVE_FAST_CLZ 1
#if defined(__LP64__)
  #define HAVE_FAST_64BIT 1
#else
  #define HAVE_FAST_64BIT 0
#endif
#define HAVE_FAST_FLOAT16 0
#define HAVE_FAST_UNALIGNED 0
#define HAVE_GETENV 1
#define HAVE_GETHRTIME 0
#define HAVE_INLINE_ASM 0
#define HAVE_INTRINSICS_NEON 0
#define HAVE_LIBC_MSVCRT 0
#define HAVE_MACH_ABSOLUTE_TIME 0
#define HAVE_MMX 0
#define HAVE_MMX_EXTERNAL 0
#define HAVE_MMX_INLINE 0
#define HAVE_MM_EMPTY 0
#define HAVE_PRAGMA_DEPRECATED 0
#define HAVE_RV 0
#define HAVE_XFORM_ASM 0

#endif
