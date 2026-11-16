import SwiftUI

/// Where syncing with the computer is, as the top bar shows it (mockup, screen 10). Not
/// "connected": the phone only connects while it syncs, a few seconds.
enum SyncState: Equatable {
    case none, upToDate, toSend(Int), syncing, problem

    @MainActor init(_ store: Store) {
        if store.computer == nil { self = .none }
        else if store.syncing { self = .syncing }
        else if store.syncProblem != nil { self = .problem }
        else if store.unsent > 0 { self = .toSend(store.unsent) }
        else { self = .upToDate }
    }
}

/// The computer's circle, next to the brand. Tapping it opens the computer's sheet, or the
/// pairing screen when there's no computer yet.
struct SyncButton: View {
    @EnvironmentObject private var store: Store
    @Environment(\.murmur) private var palette
    var openSettings: () -> Void
    @State private var sheet = false
    @State private var pairing = false

    var body: some View {
        let state = SyncState(store)
        Button {
            if state == .none { pairing = true } else { sheet = true }
        } label: {
            Image(systemName: "laptopcomputer").font(.system(size: 16, weight: .medium))
                .frame(width: 44, height: 44)
                .background(palette.fillSubtle, in: Circle())
                .overlay { if state == .syncing { SyncRing() } }
                .overlay(alignment: .topTrailing) { badge(state).offset(x: 3, y: -3) }
        }
        .buttonStyle(.plain)
        .foregroundStyle(palette.textPrimary)
        .accessibilityLabel(label(state))
        .sheet(isPresented: $sheet) {
            ComputerSheet(openSettings: {
                sheet = false
                openSettings()
            })
            .presentationDetents([.height(330)])
            .presentationDragIndicator(.visible)
        }
        .fullScreenCover(isPresented: $pairing) { PairView() }
    }

    @ViewBuilder private func badge(_ state: SyncState) -> some View {
        switch state {
        case .none: Badge(text: "+", fill: palette.textPrimary)
        case .toSend(let n): Badge(text: "\(n)", fill: palette.textPrimary)
        case .problem: Badge(text: "!", fill: palette.recordingLabel)
        default: EmptyView()
        }
    }

    private func label(_ state: SyncState) -> String {
        switch state {
        case .none: L("settings.pairComputer")
        case .upToDate: L("sync.a11y", L("sync.upToDate"))
        case .toSend(let n): L("sync.a11y", L("sync.toSend", "\(n)"))
        case .syncing: L("sync.a11y", L("sync.syncing"))
        case .problem: L("sync.a11y", store.syncProblem ?? "")
        }
    }
}

/// A small count or sign on the circle's corner, ringed with the background so it reads on top.
private struct Badge: View {
    @Environment(\.murmur) private var palette
    var text: String
    var fill: Color

    var body: some View {
        Text(text).font(.system(size: 11, weight: .bold)).monospacedDigit()
            .foregroundStyle(palette.background)
            .padding(.horizontal, 5).frame(minWidth: 18, minHeight: 18)
            .background(fill, in: Capsule())
            .overlay(Capsule().strokeBorder(palette.background, lineWidth: 2))
            .fixedSize()
    }
}

/// A quarter ring turning around the circle while it syncs (still, with Reduce Motion).
private struct SyncRing: View {
    @Environment(\.murmur) private var palette
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var turning = false

    var body: some View {
        Circle().trim(from: 0, to: 0.28)
            .stroke(palette.textPrimary, style: StrokeStyle(lineWidth: 2, lineCap: .round))
            .padding(-3)
            .rotationEffect(.degrees(turning ? 360 : 0))
            .onAppear {
                guard !reduceMotion else { return }
                withAnimation(.linear(duration: 1.1).repeatForever(autoreverses: false)) { turning = true }
            }
    }
}

/// The computer's sheet: who it is, what's happening, and what's left.
struct ComputerSheet: View {
    @EnvironmentObject private var store: Store
    @Environment(\.murmur) private var palette
    var openSettings: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 18) {
            HStack(spacing: 12) {
                Image(systemName: "laptopcomputer").font(.system(size: 20))
                    .frame(width: 48, height: 48)
                    .background(palette.fillSubtle, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                VStack(alignment: .leading, spacing: 2) {
                    Text(store.computer?.name ?? "").font(.system(size: 19, weight: .semibold)).lineLimit(1)
                    Text(status).font(.system(size: 14)).foregroundStyle(store.syncProblem != nil && !store.syncing ? palette.recordingLabel : palette.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
            VStack(spacing: 10) {
                row(L("sync.toSendRow"), "\(store.unsent)")
                row(L("sync.waiting"), "\(store.meetings.filter { $0.sent && !$0.revised }.count)")
                row(L("sync.last"), store.lastSync?.formatted(.relative(presentation: .named)) ?? L("sync.lastNever"))
            }
            .font(.system(size: 15))
            Button {
                Task { await store.sync() }
            } label: {
                Label(L("sync.now"), systemImage: "arrow.triangle.2.circlepath")
                    .font(.system(size: 17, weight: .semibold))
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .background(palette.background, in: Capsule())
                    .overlay(Capsule().strokeBorder(palette.separator))
            }
            .buttonStyle(.plain)
            .disabled(store.syncing || store.live != nil)
            .opacity(store.syncing || store.live != nil ? 0.5 : 1)
            Button(action: openSettings) {
                Text("\(L("sync.settings")) ›").font(.system(size: 15)).foregroundStyle(palette.textSecondary)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.plain)
        }
        .foregroundStyle(palette.textPrimary)
        .padding(.horizontal, 20).padding(.top, 24)
        .frame(maxHeight: .infinity, alignment: .top)
        .background(palette.background)
    }

    /// What's happening now, or how it went last time.
    private var status: String {
        if store.syncing { return L("sync.syncing") }
        if let problem = store.syncProblem { return problem }
        if let last = store.lastSync { return L("sync.synced", last.formatted(.relative(presentation: .named))) }
        return L("sync.never")
    }

    private func row(_ label: String, _ value: String) -> some View {
        HStack {
            Text(label)
            Spacer()
            Text(value).monospacedDigit().foregroundStyle(palette.textSecondary)
        }
    }
}
