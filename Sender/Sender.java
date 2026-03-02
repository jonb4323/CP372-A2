import java.io.*;
import java.net.*;
import java.util.*;

public class Sender {

    private DatagramSocket dataSocket;
    private DatagramSocket ackSocket;
    private InetAddress receiverIP;
    private int receiverPort;
    private int timeout;
    private int windowSize;
    private int base;
    private int nextSeq;
    private int timeoutCount;
    private List<DSPacket> packetList;

    public Sender(String rcvIP, int rcvDataPort, int senderAckPort, int timeoutMs, int windowSize) throws Exception {
    }

    private List<DSPacket> readFileIntoPackets(String inputFile) throws IOException {
    }

    private void performHandshake() throws IOException {
    }

    private void sendPacket(DSPacket packet) throws IOException {
    }

    private void sendWindow() throws IOException {
    }

    private List<DSPacket> applyPermutation(List<DSPacket> packets) {
    }

    private DSPacket waitForACK() throws IOException {
    }

    private void handleACK(DSPacket ackPacket) {
    }

    private boolean isInWindow(int seqNum) {
    }

    private void handleTimeout() throws IOException {
    }

    private boolean isCriticalFailure() {
    }

    private void transferFile(String inputFile) throws IOException {
    }

    private void performTeardown(int eotSeq) throws IOException {
    }

    private void close() {
    }

    public static void main(String[] args) throws Exception {
    }
}
