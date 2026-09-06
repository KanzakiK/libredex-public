#include "wordexp.h"

#include <cstdlib>
#include <cstring>

int wordexp(const char *words, wordexp_t *pwordexp, int) {
  if (!words || !pwordexp) {
    return 1;
  }

  pwordexp->we_wordc = 1;
  pwordexp->we_offs = 0;
  pwordexp->we_wordv = static_cast<char **>(std::calloc(2, sizeof(char *)));
  if (!pwordexp->we_wordv) {
    return 1;
  }

  pwordexp->we_wordv[0] = strdup(words);
  if (!pwordexp->we_wordv[0]) {
    std::free(pwordexp->we_wordv);
    pwordexp->we_wordv = nullptr;
    return 1;
  }

  return 0;
}

void wordfree(wordexp_t *pwordexp) {
  if (!pwordexp || !pwordexp->we_wordv) {
    return;
  }
  for (size_t i = 0; i < pwordexp->we_wordc; ++i) {
    std::free(pwordexp->we_wordv[i]);
  }
  std::free(pwordexp->we_wordv);
  pwordexp->we_wordv = nullptr;
  pwordexp->we_wordc = 0;
}
