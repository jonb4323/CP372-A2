import java.io.*;
import java.net.*;
import java.util.*;

public class Receiver {

    private DatagramSocket dataSocket;
    private InetAddress senderIP;
    private int senderAckPort;
    private int rcvDataPort;

    private int expectedSeq;
    private int ackCount;
    private int rn;

    private DSPacket[] buffer;
    private boolean[] received;
    private FileOutputStream fileOutput;

    public Receiver(String senderIP, int senderAckPort, int rcvDataPort, String outputFile, int rn) throws Exception {
        this.senderIP = InetAddress.getByName(senderIP);
        this.senderAckPort = senderAckPort;
        this.rcvDataPort = rcvDataPort;
        this.dataSocket = new DatagramSocket(rcvDataPort);
        this.rn = rn;
        this.fileOutput = new FileOutputStream(outputFile);

        this.expectedSeq = 1;
        this.ackCount = 0;

        this.buffer = new DSPacket[128];
        this.received = new boolean[128];
    }

    private DSPacket receivePacket() throws IOException {
        byte[] buf = new byte[DSPacket.MAX_PACKET_SIZE];
        DatagramPacket dp = new DatagramPacket(buf, buf.length);
        dataSocket.receive(dp);
        return new DSPacket(buf);
    }

    private void sendACK(int seqNum) throws IOException {
        ackCount++;

        if (ChaosEngine.shouldDrop(ackCount, rn)) {
            System.out.println("[RECEIVER] ChaosEngine dropped ACK " + seqNum + " (ackCount=" + ackCount + ")");
            return;
        }

        DSPacket ackPkt = new DSPacket(DSPacket.TYPE_ACK, seqNum, null);
        byte[] data = ackPkt.toBytes();
        DatagramPacket dp = new DatagramPacket(data, data.length, senderIP, senderAckPort);
        dataSocket.send(dp);
        System.out.println("[RECEIVER] Sent ACK " + seqNum);
    }

    private void performHandshake() throws IOException {
        System.out.println("[RECEIVER] Waiting for SOT...");
        while (true) {
            DSPacket pkt = receivePacket();
            if (pkt.getType() == DSPacket.TYPE_SOT) {
                System.out.println("[RECEIVER] Received SOT, sending ACK 0");
                sendACK(0);
                this.expectedSeq = 1;
                break;
            }
        }
    }

    private void handleDataPacket(DSPacket pkt) throws IOException {
        int seq = pkt.getSeqNum();
        int dist = (seq - expectedSeq + 128) % 128;

        if (dist == 0) {
            System.out.println("[RECEIVER] Received in-order DATA Seq=" + seq);
            fileOutput.write(pkt.getPayload());
            expectedSeq = (expectedSeq + 1) % 128;

            while (received[expectedSeq]) {
                System.out.println("[RECEIVER] Delivering buffered DATA Seq=" + expectedSeq);
                fileOutput.write(buffer[expectedSeq].getPayload());
                received[expectedSeq] = false;
                expectedSeq = (expectedSeq + 1) % 128;
            }

            int ackSeq = (expectedSeq - 1 + 128) % 128;
            sendACK(ackSeq);

        } else if (dist < 100) {
            System.out.println("[RECEIVER] Received out-of-order DATA Seq=" + seq + " (Buffered)");
            if (!received[seq]) {
                buffer[seq] = pkt;
                received[seq] = true;
            }

            int ackSeq = (expectedSeq - 1 + 128) % 128;
            sendACK(ackSeq);

        } else {
            System.out.println("[RECEIVER] Received duplicate DATA Seq=" + seq + " (Discarded)");
            int ackSeq = (expectedSeq - 1 + 128) % 128;
            sendACK(ackSeq);
        }
    }

    private void receiveFile() throws IOException {
        while (true) {
            DSPacket pkt = receivePacket();

            if (pkt.getType() == DSPacket.TYPE_SOT) {
                System.out.println("[RECEIVER] Received duplicate SOT, sending ACK 0");
                sendACK(0);
                continue;
            }

            if (pkt.getType() == DSPacket.TYPE_EOT) {
                System.out.println("[RECEIVER] Received EOT (Seq=" + pkt.getSeqNum() + ")");
                sendACK(pkt.getSeqNum());
                return;
            }

            if (pkt.getType() == DSPacket.TYPE_DATA) {
                handleDataPacket(pkt);
            }
        }
    }

    private void close() throws IOException {
        if (fileOutput != null) {
            fileOutput.close();
        }
        if (dataSocket != null && !dataSocket.isClosed()) {
            dataSocket.close();
        }
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 5) {
            System.err.println("Usage: java Receiver <sender_ip> <sender_ack_port> <rcv_data_port> <output_file> <RN>");
            System.exit(1);
        }

        String senderIP = args[0];
        int senderAckPort = Integer.parseInt(args[1]);
        int rcvDataPort = Integer.parseInt(args[2]);
        String outputFile = args[3];
        int rn = Integer.parseInt(args[4]);

        Receiver receiver = new Receiver(senderIP, senderAckPort, rcvDataPort, outputFile, rn);

        try {
            receiver.performHandshake();
            receiver.receiveFile();
        } finally {
            receiver.close();
        }
    }
}