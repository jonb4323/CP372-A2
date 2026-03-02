import java.io.*;
import java.net.*;
import java.util.*;

public class Receiver {

    private DatagramSocket dataSocket;
    private InetAddress senderIP;
    private int senderAckPort;
    private int expectedSeq;
    private int ackCount;
    private int rn;
    private int windowSize;
    private DSPacket[] buffer;
    private boolean[] received;
    private FileOutputStream fileOutput;

    public Receiver(String senderIP, int senderAckPort, int rcvDataPort, String outputFile, int rn) throws Exception {
    }

    private DSPacket receivePacket() throws IOException {
    }

    private void sendACK(int seqNum) throws IOException {
    }

    private void performHandshake() throws IOException {
    }

    private boolean isInReceiveWindow(int seqNum) {
    }

    private void bufferPacket(DSPacket packet) {
    }

    private int deliverInOrderPackets() throws IOException {
    }

    private int getCumulativeACK() {
    }

    private void receiveFile() throws IOException {
    }

    private void close() throws IOException {
    }

    public static void main(String[] args) throws Exception {
    }
}
