#pragma once

#include <algorithm>
#include <cctype>
#include <string>
#include <vector>

namespace murmur_whisper {
struct Span { double start; double end; };
struct Placed { double offset; double length; size_t span; };

/** Same 28-second speech splicing and half-second gaps as app/whisper/src/main.rs. */
inline std::vector<std::vector<Placed>> chunks(const std::vector<Span> & spans) {
    std::vector<std::vector<Placed>> result;
    double used = 28.0;
    for (size_t index = 0; index < spans.size(); ++index) {
        const double length = std::clamp(spans[index].end - spans[index].start, 0.0, 28.0);
        const double offset = used + 0.5;
        if (offset + length > 28.0) {
            result.push_back({{0.0, length, index}});
            used = length;
        } else {
            result.back().push_back({offset, length, index});
            used = offset + length;
        }
    }
    return result;
}

inline std::string trim(const std::string & value) {
    const auto begin = value.find_first_not_of(" \t\r\n");
    return begin == std::string::npos ? "" : value.substr(begin, value.find_last_not_of(" \t\r\n") - begin + 1);
}

inline std::string word_key(std::string value) {
    auto alphanumeric = [](unsigned char c) { return c >= 128 || std::isalnum(c); };
    while (!value.empty() && !alphanumeric((unsigned char)value.front())) value.erase(value.begin());
    while (!value.empty() && !alphanumeric((unsigned char)value.back())) value.pop_back();
    for (auto & character : value) if ((unsigned char)character < 128) character = std::tolower((unsigned char)character);
    return value;
}

inline bool repeats(const std::vector<std::string> & words) {
    std::vector<std::string> normalized;
    for (const auto & word : words) normalized.push_back(word_key(word));
    for (size_t count = 1; count <= 6; ++count) {
        const size_t times = count == 1 ? 8 : 4;
        for (size_t start = 0; start + count * times <= normalized.size(); ++start) {
            bool equal = true;
            for (size_t next = count; next < count * times; ++next) {
                if (normalized[start + next] != normalized[start + next % count]) { equal = false; break; }
            }
            if (equal) return true;
        }
    }
    return false;
}

inline bool hallucination(const std::string & text) {
    std::string lower = text;
    for (auto & character : lower) if ((unsigned char)character < 128) character = std::tolower((unsigned char)character);
    for (const auto * phrase : {"subtítols realitzats per", "subtítulos realizados por", "subtítulos por la comunidad", "gràcies per veure", "gracias por ver", "thanks for watching", "thank you for watching", "amara.org"}) {
        if (lower.find(phrase) != std::string::npos) return true;
    }
    return false;
}
}
