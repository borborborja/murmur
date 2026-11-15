import SwiftUI

enum Route: Hashable {
    case recording
    case meeting(String)
}

enum Tab { case meetings, settings }

@main
struct MurmurApp: App {
    @StateObject private var store = Store()
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(store).murmurTheme(.acid)
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.murmur) private var palette
    @State private var path: [Route] = []
    @State private var tab: Tab = .meetings

    var body: some View {
        NavigationStack(path: $path) {
            ZStack(alignment: .bottom) {
                palette.background.ignoresSafeArea()
                switch tab {
                case .meetings: HomeView(open: { path.append($0) })
                case .settings: SettingsView()
                }
                Dock(tab: $tab, record: record, openRecording: { path.append(.recording) })
                    .padding(.bottom, 8)
            }
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: Route.self) { route in
                switch route {
                case .recording: RecordingView(done: { id in path = id.map { [.meeting($0)] } ?? [] })
                case .meeting(let id): MeetingView(id: id)
                }
            }
        }
        .tint(palette.textPrimary)
        // `-autostart` (development, with -simulate): records at launch, to try it without touching the screen.
        .task {
            if ProcessInfo.processInfo.arguments.contains("-autostart") { record() }
        }
        .alert(store.error ?? "", isPresented: Binding(get: { store.error != nil }, set: { if !$0 { store.error = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func record() {
        Task {
            if await store.start() { path.append(.recording) }
        }
    }
}

/// The floating glass bar: Reunions · record · Configuració. While recording, the middle button
/// is the desktop's "Gravant" pill and goes back to the meeting.
struct Dock: View {
    @EnvironmentObject private var store: Store
    @Environment(\.murmur) private var palette
    @Binding var tab: Tab
    var record: () -> Void
    var openRecording: () -> Void

    var body: some View {
        HStack(spacing: 6) {
            side("list.bullet", L("dock.meetings"), on: tab == .meetings) { tab = .meetings }
            if let live = store.live {
                Button(action: openRecording) {
                    HStack(spacing: 10) {
                        LevelBars(level: live.paused ? 0 : (live.levels.last ?? 0))
                        Text(clock(live.seconds)).font(.mono(17, weight: .semibold))
                    }
                    .frame(minWidth: 120, minHeight: 56).padding(.horizontal, 18)
                    .background(palette.recordingPill, in: Capsule())
                    .overlay(Capsule().strokeBorder(palette.recordingPillBorder, lineWidth: 1))
                    .foregroundStyle(palette.onRecordingPill)
                }
                .accessibilityLabel(L("dock.recording", clock(live.seconds)))
            } else {
                Button(action: record) {
                    Image(systemName: "plus").font(.system(size: 24, weight: .semibold))
                        .frame(width: 120, height: 56)
                        .background(palette.newMeeting, in: Capsule())
                        .foregroundStyle(palette.onNewMeeting)
                }
                .accessibilityLabel(L("dock.new"))
            }
            side("gearshape", L("dock.settings"), on: tab == .settings) { tab = .settings }
        }
        .padding(6)
        .glassCapsule()
        .shadow(color: .black.opacity(0.12), radius: 15, y: 8)
        .buttonStyle(.plain)
    }

    private func side(_ icon: String, _ label: String, on: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Image(systemName: icon).font(.system(size: 19, weight: .medium))
                .frame(width: 56, height: 56)
                .background(on ? palette.textPrimary.opacity(0.08) : .clear, in: Circle())
                .foregroundStyle(on ? palette.textPrimary : palette.textSecondary)
        }
        .accessibilityLabel(label)
    }
}

struct HomeView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.murmur) private var palette
    var open: (Route) -> Void

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                HStack(spacing: 9) {
                    LogoTile(size: 34)
                    Text("murmur").font(.system(size: 24, weight: .bold)).tracking(-1.1)
                }
                Spacer()
            }
            .padding(.horizontal, 20).frame(height: 56)

            if store.meetings.isEmpty && store.live == nil {
                EmptyHome()
            } else {
                ScrollView {
                    LazyVStack(alignment: .leading, spacing: 10) {
                        if let live = store.live {
                            section(L("home.today"))
                            LiveCard(live: live).onTapGesture { open(.recording) }
                        }
                        ForEach(groups, id: \.0) { title, rows in
                            if !(store.live != nil && title == L("home.today")) { section(title) }
                            ForEach(rows) { row in
                                MeetingCard(row: row).onTapGesture { open(.meeting(row.id)) }
                            }
                        }
                    }
                    .padding(.horizontal, 16).padding(.bottom, 120)
                }
            }
        }
        .foregroundStyle(palette.textPrimary)
    }

    private func section(_ title: String) -> some View {
        Text(title).font(.system(size: 13, weight: .semibold)).foregroundStyle(palette.textSecondary)
            .padding(.leading, 8).padding(.top, 14)
    }

    /// Avui, Ahir, Aquesta setmana, Abans: as the desktop's sidebar.
    private var groups: [(String, [MeetingRow])] {
        let cal = Calendar.current
        var out: [(String, [MeetingRow])] = []
        for row in store.meetings {
            let date = Date(timeIntervalSince1970: row.createdAt / 1000)
            let title = cal.isDateInToday(date) ? L("home.today") : cal.isDateInYesterday(date) ? L("home.yesterday")
                : cal.isDate(date, equalTo: .now, toGranularity: .weekOfYear) ? L("home.thisWeek") : L("home.earlier")
            if out.last?.0 == title { out[out.count - 1].1.append(row) } else { out.append((title, [row])) }
        }
        return out
    }
}

struct EmptyHome: View {
    @EnvironmentObject private var store: Store
    @Environment(\.murmur) private var palette
    @Environment(\.colorScheme) private var scheme

    var body: some View {
        ZStack(alignment: .bottom) {
            RadialGradient(colors: [palette.accent.opacity(scheme == .dark ? 0.16 : 0.34), .clear], center: .bottom, startRadius: 0, endRadius: 260)
                .frame(height: 420).offset(y: 120).allowsHitTesting(false)
            VStack(spacing: 10) {
                LogoTile(size: 84).padding(.bottom, 16)
                Text(L("home.emptyTitle")).font(.system(size: 28, weight: .bold)).tracking(-0.5)
                Text(L("home.emptyText")).font(.system(size: 17)).foregroundStyle(palette.textSecondary)
                Text(L("home.consent")).font(.system(size: 13)).foregroundStyle(palette.textTertiary)
                Spacer()
                Text(languageName(store.language)).font(.system(size: 13)).foregroundStyle(palette.textSecondary)
                    .padding(.bottom, 90)
            }
            .multilineTextAlignment(.center)
            .padding(.horizontal, 36).padding(.top, 70)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea(edges: .bottom)
    }
}

func languageName(_ code: String) -> String {
    switch code {
    case "ca": return L("language.ca")
    case "es": return L("language.es")
    case "en": return L("language.en")
    default: return L("language.caEs")
    }
}

struct LiveCard: View {
    @Environment(\.murmur) private var palette
    var live: LiveMeeting
    var body: some View {
        HStack(alignment: .top, spacing: 13) {
            Circle().fill(palette.recordingPill).frame(width: 40, height: 40)
                .overlay(LevelBars(level: live.levels.last ?? 0, count: 3, height: 15))
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    Text(live.title).font(.system(size: 17, weight: .semibold)).lineLimit(1)
                    Spacer()
                    HStack(spacing: 6) { RecordingDot(); Text(clock(live.seconds)).font(.mono(13, weight: .bold)) }
                        .foregroundStyle(palette.recordingLabel)
                }
                Text(live.lastLine.isEmpty ? L("rec.listening") : "…" + live.lastLine)
                    .font(.system(size: 15)).lineLimit(1)
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .background(palette.background, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 22, style: .continuous).strokeBorder(palette.textPrimary, lineWidth: 1.5))
        .contentShape(Rectangle())
    }
}

struct MeetingCard: View {
    @Environment(\.murmur) private var palette
    var row: MeetingRow
    var body: some View {
        HStack(alignment: .top, spacing: 13) {
            Image(systemName: row.revised ? "laptopcomputer" : "iphone").font(.system(size: 16))
                .frame(width: 40, height: 40).background(palette.background, in: Circle())
                .foregroundStyle(palette.textSecondary)
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline) {
                    Text(row.title).font(.system(size: 17, weight: .semibold)).lineLimit(1)
                    Spacer()
                    Text("\(max(1, Int(row.duration / 60))) min").font(.system(size: 15)).monospacedDigit().foregroundStyle(palette.textSecondary)
                }
                HStack(spacing: 6) {
                    Text(Date(timeIntervalSince1970: row.createdAt / 1000).formatted(date: .omitted, time: .shortened))
                    if row.revised { Text("· " + L("home.revised")) }
                    ForEach(row.tags, id: \.self) { tag in
                        Text(tag).font(.system(size: 12, weight: .medium)).padding(.horizontal, 7).padding(.vertical, 2)
                            .background(palette.background, in: RoundedRectangle(cornerRadius: 5))
                    }
                }
                .font(.system(size: 13)).foregroundStyle(palette.textSecondary)
                if let last = row.lastLine {
                    Text(last).font(.system(size: 15)).foregroundStyle(palette.textSecondary).lineLimit(1).padding(.top, 4)
                }
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 14)
        .background(palette.fillSubtle, in: RoundedRectangle(cornerRadius: 22, style: .continuous))
        .contentShape(Rectangle())
    }
}
