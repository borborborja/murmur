// murmur-probe (ffi/src/lib.rs): the engine's C functions. Strings it returns are JSON, freed
// with mm_string_free.
#include <stdbool.h>
#include <stddef.h>
#include <stdint.h>

typedef struct Probe Probe;

Probe *mm_probe_new(const char *models_dir, bool int8, int32_t threads);
char *mm_load_error(void);
double mm_probe_load_seconds(const Probe *probe);
char *mm_live_start(Probe *probe, bool partials);
char *mm_live_push(Probe *probe, const float *samples, size_t count);
char *mm_live_finish(Probe *probe);
char *mm_bench_file(Probe *probe, const char *wav_path, bool partials);
void mm_string_free(char *s);
