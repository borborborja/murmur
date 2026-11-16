import AVFoundation
import SwiftUI

/// "Connecta l'ordinador" (mockup, screen 8): the camera reads the QR on the computer's screen,
/// and the card below says where to find it. The camera's area is always dark, so the acid frame
/// sits on dark (DESIGN.md: acid is never a line on light).
struct PairView: View {
    @EnvironmentObject private var store: Store
    @Environment(\.murmur) private var palette
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL

    private enum Phase: Equatable {
        case scanning
        case connecting(String)
        case done(String)
        case problem(String)
    }

    @State private var phase: Phase = .scanning
    @State private var camera: AVAuthorizationStatus = AVCaptureDevice.authorizationStatus(for: .video)
    private let dark = MurmurPalette.acidDark

    var body: some View {
        ZStack(alignment: .bottom) {
            dark.background.ignoresSafeArea()
            if camera == .authorized, phase == .scanning || isProblem {
                QRScanner { code in read(code) }.ignoresSafeArea()
            }
            VStack(spacing: 0) {
                HStack {
                    Button { dismiss() } label: {
                        Image(systemName: "chevron.left").font(.system(size: 17, weight: .semibold))
                            .frame(width: 44, height: 44).background(.ultraThinMaterial, in: Circle())
                    }
                    .accessibilityLabel(L("rec.back"))
                    Spacer()
                    Text(L("pair.title")).font(.system(size: 17, weight: .semibold))
                    Spacer()
                    Color.clear.frame(width: 44, height: 44)
                }
                .foregroundStyle(dark.textPrimary)
                .padding(.horizontal, 16)
                frame.frame(maxHeight: .infinity)
                card
            }
        }
        .task {
            // Ask now for the local network (the first Bonjour search does), while the person goes
            // to the computer: by the time the code is read, the answer is in.
            _ = await Discovery.find(timeout: 0.5)
            if camera == .notDetermined {
                let granted = await AVCaptureDevice.requestAccess(for: .video)
                camera = granted ? .authorized : .denied
            }
        }
    }

    private var isProblem: Bool { if case .problem = phase { true } else { false } }

    /// The acid corners where the code goes; a tick once it's connected.
    private var frame: some View {
        ZStack {
            Corners().stroke(dark.accent, style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round))
                .frame(width: 220, height: 220)
            switch phase {
            case .connecting: ProgressView().tint(dark.textPrimary).controlSize(.large)
            case .done:
                Image(systemName: "checkmark").font(.system(size: 56, weight: .semibold)).foregroundStyle(dark.accent)
                    .transition(.scale.combined(with: .opacity))
            default: EmptyView()
            }
        }
        .accessibilityHidden(true)
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 14) {
            Text(L("pair.heading")).font(.system(size: 22, weight: .bold)).tracking(-0.3)
            if camera == .denied || camera == .restricted {
                Text(L("pair.noCamera")).font(.system(size: 15)).foregroundStyle(palette.textSecondary)
                Button(L("pair.openSettings")) {
                    if let url = URL(string: UIApplication.openSettingsURLString) { openURL(url) }
                }
                .font(.system(size: 15, weight: .semibold))
            } else {
                VStack(alignment: .leading, spacing: 10) {
                    step(1, L("pair.step1"))
                    step(2, L("pair.step2"))
                    step(3, L("pair.step3"))
                }
            }
            status
            Text(L("pair.after")).font(.system(size: 14)).foregroundStyle(palette.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .foregroundStyle(palette.textPrimary)
        .padding(24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(palette.background, in: UnevenRoundedRectangle(topLeadingRadius: 32, topTrailingRadius: 32, style: .continuous))
        .ignoresSafeArea(edges: .bottom)
    }

    @ViewBuilder private var status: some View {
        switch phase {
        case .scanning: EmptyView()
        case .connecting(let name):
            Label(L("pair.connecting", name), systemImage: "laptopcomputer").font(.system(size: 15, weight: .medium))
        case .done(let name):
            Label(L("pair.done", name), systemImage: "checkmark.circle.fill").font(.system(size: 15, weight: .semibold))
        case .problem(let message):
            Label(message, systemImage: "exclamationmark.triangle").font(.system(size: 15, weight: .medium))
                .foregroundStyle(palette.textPrimary)
        }
    }

    private func step(_ n: Int, _ text: String) -> some View {
        HStack(alignment: .firstTextBaseline, spacing: 12) {
            Text("\(n)").font(.system(size: 13, weight: .semibold)).frame(width: 24, height: 24)
                .background(palette.fillSubtle, in: Circle())
            Text(text).font(.system(size: 16)).fixedSize(horizontal: false, vertical: true)
        }
    }

    private func read(_ code: String) {
        guard phase == .scanning || isProblem else { return }
        guard let info = try? readPairingCode(uri: code) else {
            phase = .problem(L("pair.notACode"))
            return
        }
        UIImpactFeedbackGenerator(style: .medium).impactOccurred()
        phase = .connecting(info.computerName)
        Task {
            do {
                let name = try await store.pair(code: code)
                withAnimation(.spring(duration: 0.35)) { phase = .done(name) }
                UINotificationFeedbackGenerator().notificationOccurred(.success)
                try? await Task.sleep(for: .seconds(1.2))
                dismiss()
            } catch {
                UINotificationFeedbackGenerator().notificationOccurred(.error)
                phase = .problem(error.localizedDescription)
            }
        }
    }
}

/// Four corners of a square, as a viewfinder.
private struct Corners: Shape {
    func path(in rect: CGRect) -> Path {
        let l = rect.width * 0.22
        var p = Path()
        for (corner, dx, dy) in [(CGPoint(x: rect.minX, y: rect.minY), 1.0, 1.0), (CGPoint(x: rect.maxX, y: rect.minY), -1.0, 1.0),
                                 (CGPoint(x: rect.minX, y: rect.maxY), 1.0, -1.0), (CGPoint(x: rect.maxX, y: rect.maxY), -1.0, -1.0)] {
            p.move(to: CGPoint(x: corner.x, y: corner.y + dy * l))
            p.addLine(to: corner)
            p.addLine(to: CGPoint(x: corner.x + dx * l, y: corner.y))
        }
        return p
    }
}

/// The camera, reading QR codes. Calls `found` with each code's text (the caller ignores repeats).
struct QRScanner: UIViewRepresentable {
    var found: (String) -> Void

    final class Preview: UIView {
        override class var layerClass: AnyClass { AVCaptureVideoPreviewLayer.self }
        var preview: AVCaptureVideoPreviewLayer { layer as! AVCaptureVideoPreviewLayer }
        let session = AVCaptureSession()
        var delegate: Delegate?
        deinit { let s = session; DispatchQueue.global(qos: .userInitiated).async { s.stopRunning() } }
    }

    final class Delegate: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        var found: (String) -> Void
        init(found: @escaping (String) -> Void) { self.found = found }
        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject], from connection: AVCaptureConnection) {
            if let code = (objects.first as? AVMetadataMachineReadableCodeObject)?.stringValue { found(code) }
        }
    }

    func makeUIView(context: Context) -> Preview {
        let view = Preview()
        view.preview.videoGravity = .resizeAspectFill
        let delegate = Delegate(found: found)
        view.delegate = delegate
        let session = view.session
        guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else { return view }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else { return view }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(delegate, queue: .main)
        output.metadataObjectTypes = [.qr]
        view.preview.session = session
        DispatchQueue.global(qos: .userInitiated).async { session.startRunning() }
        return view
    }

    func updateUIView(_ view: Preview, context: Context) {
        view.delegate?.found = found
    }
}
