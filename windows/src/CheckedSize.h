#pragma once
#include <stdexcept>
#include <utility>

namespace bridge {
// Check before crossing APIs with narrower or signed length parameters.
template<class To, class From> To CheckedSize(From value) {
    if(!std::in_range<To>(value)) throw std::length_error("Length exceeds Windows API limit");
    return static_cast<To>(value);
}
}
