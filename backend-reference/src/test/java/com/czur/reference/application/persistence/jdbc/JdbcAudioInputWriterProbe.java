package com.czur.reference.application.persistence.jdbc;

import com.czur.reference.application.port.DurableAudioInputPort;
import org.postgresql.ds.PGSimpleDataSource;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetAddress;
import java.net.Socket;

/** 两个真实子 JVM 使用协调连接屏障并发绑定，再验证最大 epoch 独占写权。 */
public final class JdbcAudioInputWriterProbe {
    private JdbcAudioInputWriterProbe() { }

    public static void main(String[] args) {
        try {
            if (args.length != 2 || !(args[1].equals("a") || args[1].equals("b"))) {
                throw new IllegalArgumentException("probe port and role required");
            }
            PGSimpleDataSource source = JdbcAudioInputRecoveryIntegrationTest.source();
            source.setApplicationName("t16-audio-probe-" + args[1]);
            JdbcDurableAudioInputPort port = JdbcAudioInputRecoveryIntegrationTest.port(
                    source);
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(),
                    Integer.parseInt(args[0]))) {
                socket.setSoTimeout(30_000);
                DataInputStream in = new DataInputStream(socket.getInputStream());
                DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                out.writeUTF(args[1]);
                out.flush();
                in.readByte();
                DurableAudioInputPort.Bound bound = (DurableAudioInputPort.Bound) port.bindWriter(
                        JdbcAudioInputRecoveryIntegrationTest.MEETING,
                        DurableAudioInputPort.WriterConnectionId.newConnection());
                long epoch = bound.binding().writerEpoch();
                out.writeUTF(Long.toString(epoch));
                out.flush();
                in.readByte();
                var frameResult = port.append(bound.binding(),
                        JdbcAudioInputRecoveryIntegrationTest.frame(2, 20, (byte) epoch));
                var completeResult = port.complete(bound.binding(),
                        JdbcAudioInputRecoveryIntegrationTest.completion(2, 40));
                out.writeUTF(status(frameResult) + " " + status(completeResult));
                out.flush();
            }
        } catch (Throwable failure) {
            System.err.println("WRITER_PROBE_FAILED type=" + failure.getClass().getSimpleName());
            System.exit(2);
        }
    }

    private static String status(DurableAudioInputPort.WriteResult result) {
        if (result instanceof DurableAudioInputPort.Stored) return "STORED";
        if (result instanceof DurableAudioInputPort.Rejected rejected
                && rejected.reason() == DurableAudioInputPort.Rejection.FENCED) return "FENCED";
        return result.getClass().getSimpleName();
    }
}
