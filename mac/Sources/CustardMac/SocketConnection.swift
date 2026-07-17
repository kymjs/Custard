import Darwin
import Foundation

final class SocketConnection: @unchecked Sendable {
    private var fileDescriptor: Int32 = -1
    private let host: String
    private let port: UInt16
    private let ioQueue = DispatchQueue(label: "com.kymjs.custard.socket.io", qos: .userInteractive)
    private(set) var handshakeValidated = false

    init(host: String, port: UInt16) {
        self.host = host
        self.port = port
    }

    func open() async throws {
        try await withCheckedThrowingContinuation { (continuation: CheckedContinuation<Void, Error>) in
            DispatchQueue.global(qos: .userInitiated).async {
                do {
                    try self.openSocketPOSIX()
                    continuation.resume()
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    private func openSocketPOSIX() throws {
        Logger.info("POSIX connect begin host=\(host) port=\(port)")

        var addr = sockaddr_in()
        memset(&addr, 0, MemoryLayout<sockaddr_in>.size)
        addr.sin_len = UInt8(MemoryLayout<sockaddr_in>.size)
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_port = in_port_t(port).bigEndian

        let parseResult: Int32 = host.withCString { cString in
            inet_pton(AF_INET, cString, &addr.sin_addr)
        }
        Logger.info("inet_pton result=\(parseResult) errno=\(errno)")
        guard parseResult == 1 else {
            throw POSIXError(.EINVAL)
        }

        let fd = Darwin.socket(AF_INET, SOCK_STREAM, IPPROTO_TCP)
        Logger.info("socket() returned fd=\(fd) errno=\(errno)")
        guard fd >= 0 else {
            throw POSIXError(.init(rawValue: errno) ?? .EIO)
        }

        let connectResult: Int32 = withUnsafePointer(to: &addr) { pointer in
            pointer.withMemoryRebound(to: sockaddr.self, capacity: 1) { sockAddr in
                Darwin.connect(fd, sockAddr, socklen_t(MemoryLayout<sockaddr_in>.size))
            }
        }
        Logger.info("connect() returned \(connectResult) errno=\(errno)")

        guard connectResult == 0 else {
            Darwin.close(fd)
            throw POSIXError(.init(rawValue: errno) ?? .ECONNREFUSED)
        }

        var noDelay: Int32 = 1
        setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &noDelay, socklen_t(MemoryLayout<Int32>.size))

        fileDescriptor = fd
        Logger.info("POSIX connect success fd=\(fd)")
    }

    /// 验证服务端握手（读取 MAGIC），避免 ADB 假连接
    func validateHandshake() async throws {
        let magic = try await readExact(count: Protocol.magic.count)
        guard String(data: magic, encoding: .utf8) == Protocol.magic else {
            Logger.warn("invalid magic: \(magic.map { String(format: "%02x", $0) }.joined())")
            close()
            throw ConnectionError.invalidMagic
        }
        handshakeValidated = true
        Logger.info("handshake validated")
    }

    func readExact(count: Int) async throws -> Data {
        try await withCheckedThrowingContinuation { continuation in
            ioQueue.async {
                do {
                    let data = try self.readExactSync(count: count)
                    continuation.resume(returning: data)
                } catch {
                    continuation.resume(throwing: error)
                }
            }
        }
    }

    func readExactBlocking(count: Int) throws -> Data {
        try ioQueue.sync {
            try readExactSync(count: count)
        }
    }

    private func readExactSync(count: Int) throws -> Data {
        guard fileDescriptor >= 0 else { throw ConnectionError.connectionClosed }
        var buffer = Data(count: count)
        var totalRead = 0
        while totalRead < count {
            let readCount = buffer.withUnsafeMutableBytes { ptr in
                Darwin.read(fileDescriptor, ptr.baseAddress!.advanced(by: totalRead), count - totalRead)
            }
            if readCount < 0 {
                Logger.warn("read() error fd=\(fileDescriptor) errno=\(errno)")
                throw ConnectionError.connectionClosed
            }
            if readCount == 0 {
                Logger.warn("read() EOF fd=\(fileDescriptor) (peer closed, server may not be running)")
                throw ConnectionError.serverNotRunning
            }
            totalRead += readCount
        }
        return buffer
    }

    func send(_ data: Data) throws {
        guard fileDescriptor >= 0 else { return }
        try data.withUnsafeBytes { ptr in
            var sent = 0
            while sent < data.count {
                let result = Darwin.write(fileDescriptor, ptr.baseAddress!.advanced(by: sent), data.count - sent)
                if result <= 0 {
                    throw ConnectionError.connectionClosed
                }
                sent += result
            }
        }
    }

    func close() {
        if fileDescriptor >= 0 {
            Darwin.close(fileDescriptor)
            fileDescriptor = -1
        }
    }

    deinit {
        close()
    }
}
