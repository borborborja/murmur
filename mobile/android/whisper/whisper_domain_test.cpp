#include "whisper_domain.h"
#include <cassert>
#include <iostream>

int main() {
    std::vector<murmur_whisper::Span> spans;
    for (int i = 0; i < 10; ++i) spans.push_back({i * 10.0, i * 10.0 + 8.0});
    const auto grouped = murmur_whisper::chunks(spans);
    assert(grouped.size() == 4);
    assert(grouped[0].size() == 3 && grouped[1].size() == 3 && grouped[2].size() == 3 && grouped[3].size() == 1);
    assert(grouped[0][1].offset == 8.5);
    assert(grouped[0][2].offset + grouped[0][2].length == 25.0);
    assert(murmur_whisper::repeats({"a", "b,", "a", "b,", "a", "b,", "a", "b"}));
    assert(murmur_whisper::repeats({"sí", "sí", "sí", "sí", "sí", "sí", "sí", "sí"}));
    assert(!murmur_whisper::repeats({"clic,", "clic,", "clic,", "clic,", "clic"}));
    assert(!murmur_whisper::repeats({"sí", "sí", "sí", "no"}));
    assert(murmur_whisper::hallucination("Gràcies per veure aquest vídeo"));
    assert(murmur_whisper::hallucination("THANKS FOR WATCHING"));
    assert(!murmur_whisper::hallucination("El pressupost és de 50 euros"));
    assert(murmur_whisper::chunks({}).empty());
    std::cout << "Whisper chunking and hallucination fixtures passed\n";
}
