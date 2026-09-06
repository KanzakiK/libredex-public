#ifdef _FORTIFY_SOURCE
  #undef _FORTIFY_SOURCE
#endif

#ifndef NDEBUG
  #define NDEBUG
#endif

#define reed_solomon_init reed_solomon_init_def
#define reed_solomon_new reed_solomon_new_def
#define reed_solomon_new_static reed_solomon_new_static_def
#define reed_solomon_release reed_solomon_release_def
#define reed_solomon_decode reed_solomon_decode_def
#define reed_solomon_encode reed_solomon_encode_def

#include "third-party/nanors/deps/obl/autoshim.h"
#include "third-party/nanors/rs.c"

#undef reed_solomon_init
#undef reed_solomon_new
#undef reed_solomon_new_static
#undef reed_solomon_release
#undef reed_solomon_decode
#undef reed_solomon_encode

#include "src/rswrapper.h"

reed_solomon_new_t reed_solomon_new_fn;
reed_solomon_release_t reed_solomon_release_fn;
reed_solomon_encode_t reed_solomon_encode_fn;
reed_solomon_decode_t reed_solomon_decode_fn;

void reed_solomon_init(void) {
  reed_solomon_new_fn = reed_solomon_new_def;
  reed_solomon_release_fn = reed_solomon_release_def;
  reed_solomon_encode_fn = reed_solomon_encode_def;
  reed_solomon_decode_fn = reed_solomon_decode_def;
  reed_solomon_init_def();
}
