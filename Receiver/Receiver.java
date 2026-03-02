import java.io.*;
import java.net.*;
import java.util.*;

public class Receiver {

    private DatagramSocket dataSocket;  // listens for incoming DATA packets
    private InetAddress senderIP;
    private int senderAckPort;
    private int expectedSeq;
    private int ackCount;               // 1-indexed, tracks every ACK intended to send
    private int rn;                     // Reliability Number for ChaosEngine
    private int windowSize;
    private DSPacket[] buffer;          // circular buffer for GBN out-of-order packets
    private boolean[] received;         // tracks which buffer slots are filled
    private FileOutputStream fileOutput;

    // Track last cumulative ACK sent (for GBN re-sends on duplicate/out-of-window)
    private int lastACKSent;

    /**
     * Constructor — sets up socket, file output, and buffer.
     *
     * windowSize = -1 signals Stop-and-Wait mode (no buffer needed).
     */
    public Receiver(String senderIP, int senderAckPort, int rcvDataPort, String outputFile, int rn) throws Exception {
        this.senderIP     = InetAddress.getByName(senderIP);
        this.senderAckPort = senderAckPort;
        this.rn           = rn;
        this.expectedSeq  = 1;   // first DATA packet expected has Seq=1
        this.ackCount     = 0;   // incremented before each intended ACK send
        this.lastACKSent  = 0;
        this.windowSize   = -1;  // set later if GBN

        // Bind data socket to rcvDataPort
        this.dataSocket = new DatagramSocket(rcvDataPort);

        // Open output file for writing
        this.fileOutput = new FileOutputStream(new File(outputFile));
    }

    /**
     * Receives a single 128-byte UDP datagram and parses it into a DSPacket.
     */
    private DSPacket receivePacket() throws IOException {
        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        dataSocket.receive(dp);
        return new DSPacket(buf);
    }

    /**
     * Sends an ACK for the given sequence number.
     * Uses ChaosEngine.shouldDrop() to simulate ACK loss based on RN.
     * ackCount is always incremented (1-indexed) before the drop check.
     */
    private void sendACK(int seqNum) throws IOException {
        ackCount++;  // increment regardless of drop

        if (ChaosEngine.shouldDrop(ackCount, rn)) {
            System.out.println("[RECEIVER] ACK " + seqNum + " DROPPED (ackCount=" + ackCount + ")");
            return;
        }

        DSPacket ack = new DSPacket(DSPacket.TYPE_ACK, seqNum, null);
        byte[] data = ack.toBytes();
        DatagramPacket dp = new DatagramPacket(data, data.length, senderIP, senderAckPort);
        dataSocket.send(dp);

        lastACKSent = seqNum;
        System.out.println("[RECEIVER] ACK sent Seq=" + seqNum);
    }

    /**
     * Phase 1: Handshake
     * Waits for SOT (Type=0, Seq=0), replies with ACK 0.
     */
    private void performHandshake() throws IOException {
        System.out.println("[RECEIVER] Waiting for SOT...");

        while (true) {
            DSPacket pkt = receivePacket();

            if (pkt.getType() == DSPacket.TYPE_SOT && pkt.getSeqNum() == 0) {
                System.out.println("[RECEIVER] SOT received — sending ACK 0");
                sendACK(0);
                return;
            } else {
                System.out.println("[RECEIVER] Unexpected packet during handshake, ignoring");
            }
        }
    }

    /**
     * GBN: Returns true if seqNum is within the receive window [expectedSeq, expectedSeq+windowSize).
     * Handles mod-128 wrap-around.
     */
    private boolean isInReceiveWindow(int seqNum) {
        return ((seqNum - expectedSeq + 128) % 128) < windowSize;
    }

    /**
     * GBN: Buffers a received out-of-order packet at its position in the circular buffer.
     */
    private void bufferPacket(DSPacket packet) {
        int seq = packet.getSeqNum();
        int idx = seq % windowSize;

        if (!received[idx]) {
            buffer[idx] = packet;
            received[idx] = true;
            System.out.println("[RECEIVER] GBN Buffered Seq=" + seq);
        }
    }

    /**
     * GBN: Delivers all contiguous in-order packets starting from expectedSeq.
     * Writes their payloads to the output file.
     *
     * @return the new expectedSeq after delivery
     */
    private int deliverInOrderPackets() throws IOException {
        while (true) {
            int idx = expectedSeq % windowSize;

            if (!received[idx]) break;

            DSPacket pkt = buffer[idx];

            // Confirm it's actually the right seq (guard against stale buffer slot)
            if (pkt.getSeqNum() != expectedSeq) break;

            fileOutput.write(pkt.getPayload());
            System.out.println("[RECEIVER] GBN Delivered Seq=" + expectedSeq);

            received[idx] = false;
            buffer[idx]   = null;
            expectedSeq   = (expectedSeq + 1) % 128;
            if (expectedSeq == 0) expectedSeq = 128 % 128; // keep consistent
        }

        return expectedSeq;
    }

    /**
     * GBN: Returns the cumulative ACK — the last contiguous in-order seq delivered.
     * This is (expectedSeq - 1 + 128) % 128.
     */
    private int getCumulativeACK() {
        return (expectedSeq - 1 + 128) % 128;
    }

    /**
     * Main receive loop — handles both Stop-and-Wait and GBN.
     * Determined by whether windowSize has been set (> 0).
     */
    private void receiveFile() throws IOException {
        boolean isGBN = (windowSize > 0);

        if (isGBN) {
            buffer   = new DSPacket[windowSize];
            received = new boolean[windowSize];
            Arrays.fill(received, false);
        }

        while (true) {
            DSPacket pkt = receivePacket();
            byte type    = pkt.getType();
            int seq      = pkt.getSeqNum();

            // --- EOT handling ---
            if (type == DSPacket.TYPE_EOT) {
                System.out.println("[RECEIVER] EOT received Seq=" + seq);
                sendACK(seq);
                return;
            }

            // --- DATA handling ---
            if (type == DSPacket.TYPE_DATA) {

                if (!isGBN) {
                    // ---- Stop-and-Wait ----
                    if (seq == expectedSeq) {
                        fileOutput.write(pkt.getPayload());
                        System.out.println("[RECEIVER] S&W Received in-order Seq=" + seq);
                        sendACK(seq);
                        expectedSeq = (expectedSeq + 1) % 128;
                    } else {
                        // Duplicate or out-of-order — re-ACK the last in-order packet
                        int reAck = (expectedSeq - 1 + 128) % 128;
                        System.out.println("[RECEIVER] S&W Out-of-order Seq=" + seq + ", re-ACKing " + reAck);
                        sendACK(reAck);
                    }

                } else {
                    // ---- Go-Back-N ----
                    if (isInReceiveWindow(seq)) {
                        bufferPacket(pkt);
                        deliverInOrderPackets();

                        int cumACK = getCumulativeACK();
                        System.out.println("[RECEIVER] GBN In-window Seq=" + seq + ", cumACK=" + cumACK);
                        sendACK(cumACK);

                    } else {
                        // Below or above window — discard and re-send cumulative ACK
                        int cumACK = getCumulativeACK();
                        System.out.println("[RECEIVER] GBN Out-of-window Seq=" + seq + ", re-ACKing cumACK=" + cumACK);
                        sendACK(cumACK);
                    }
                }
            }
        }
    }

    /**
     * Closes the data socket and output file stream.
     */
    private void close() throws IOException {
        if (dataSocket != null && !dataSocket.isClosed()) dataSocket.close();
        if (fileOutput != null) fileOutput.close();
        System.out.println("[RECEIVER] Closed — file written successfully");
    }

    /**
     * Entry point.
     *
     * Usage:
     *   java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>
     *
     * Optional 6th argument: window_size (for GBN mode)
     * If omitted → Stop-and-Wait
     */
    public static void main(String[] args) throws Exception {
        if (args.length < 5 || args.length > 6) {
            System.err.println("Usage: java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN> [window_size]");
            System.exit(1);
        }

        String senderIP    = args[0];
        int senderAckPort  = Integer.parseInt(args[1]);
        int rcvDataPort    = Integer.parseInt(args[2]);
        String outputFile  = args[3];
        int rn             = Integer.parseInt(args[4]);
        int windowSize     = (args.length == 6) ? Integer.parseInt(args[5]) : -1;

        Receiver receiver = new Receiver(senderIP, senderAckPort, rcvDataPort, outputFile, rn);

        // Set window size if GBN
        if (windowSize > 0) {
            receiver.windowSize = windowSize;
        }

        try {
            receiver.performHandshake();
            receiver.receiveFile();
        } finally {
            receiver.close();
        }
    }
}