import Foundation

/// The phone's texts: app/src/locales/<lang>/mobile.json, made into Localizable.xcstrings by
/// tools/mobile-strings.mjs ({{x}} becomes %@).
func L(_ key: String, _ args: CVarArg...) -> String {
    let format = Bundle.main.localizedString(forKey: key, value: nil, table: nil)
    return args.isEmpty ? format : String(format: format, arguments: args)
}

func clock(_ seconds: Double) -> String {
    let s = Int(seconds)
    return s >= 3600 ? String(format: "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) : String(format: "%d:%02d", s / 60, s % 60)
}

/// "00:12:34", as the recording card shows it.
func longClock(_ seconds: Double) -> String {
    let s = Int(seconds)
    return String(format: "%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60)
}
