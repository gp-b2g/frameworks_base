/*
 * Copyright (c) 2011, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *    * Redistributions of source code must retain the above copyright
 *      notice, this list of conditions and the following disclaimer.
 *    * Redistributions in binary form must reproduce the above copyright
 *      notice, this list of conditions and the following disclaimer in the
 *      documentation and/or other materials provided with the distribution.
 *    * Neither the name of Code Aurora nor
 *      the names of its contributors may be used to endorse or promote
 *      products derived from this software without specific prior written
 *      permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NON-INFRINGEMENT ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS;
 * OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR
 * OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF
 * ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.quicinc.l2cap_test;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.BluetoothServerSocket;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Random;

public class L2CapTest {
    private static final int RFCOMM_CHANNEL = 3;
    private static final int L2CAP_PSM_CHANNEL = 5257;
    private static final int REPORTING_INTERVAL_MS = 200;
    private static final int BUFFER_SIZE = 1024;

    private int fileSize = 0; // Number of bytes that the client will [attempt to] transfer
    private int bytesTrans = 0; // Number of bytes transfered
    private int errorTrans = 0; // Number of transmission errors (error if rx != tx per byte)
    private long transferStartTime = 0; // Timestamp of transmission start

    private boolean runServerReporter = false;
    private boolean runClientReporter = false;

    private void printClientTransferStatus() {
        long currentTime = SystemClock.elapsedRealtime();
        double transferRate = (double)bytesTrans*1000/(double)(currentTime - transferStartTime);

        System.out.println("--------");
        System.out.println("Transferred " + Integer.toString(bytesTrans) + "/" + Integer.toString(fileSize) +
                " bytes (" + Double.toString((double)bytesTrans*100/fileSize) + "%)");

        System.out.println(Integer.toString(errorTrans) +
                " (" + Double.toString((double)errorTrans*100/bytesTrans) + "%) Errors");

        System.out.println(Double.toString(transferRate) + " bytes/sec");
    }

    private class ClientReporter extends Thread {
        @Override
        public void run() {
            while(runClientReporter) {
                try {
                    sleep(REPORTING_INTERVAL_MS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                printClientTransferStatus();
            }
        }
    }


    private void clientLooper(BluetoothSocket bs, FileInputStream fis, FileInputStream vis) throws IOException {
        byte buf[] = new byte[BUFFER_SIZE];

        InputStream is = bs.getInputStream();
        OutputStream os = bs.getOutputStream();

        int bytesReadFile;
        int bytesReadSpp = 0;
        while ((bytesReadFile = fis.read(buf)) > 0) {
            os.write(buf, 0, bytesReadFile);
            while (bytesReadSpp < bytesReadFile) {
                int bytesRead = is.read(buf, bytesReadSpp, (bytesReadFile-bytesReadSpp));

                bytesReadSpp = bytesReadSpp + bytesRead;

                // Add to total bytes transferred after round-trip
                bytesTrans = bytesTrans + bytesRead;
            }

            // Verification of read data
            for (int i=0;i<bytesReadSpp;i++) {
                if (vis.read() != buf[i]) {
                    errorTrans++;
                }
            }
        }

        is.close();
        os.close();
    }

    private void clientLooper(BluetoothSocket bs) throws IOException {
        byte buf[] = new byte[BUFFER_SIZE];
        byte buf2[] = new byte[BUFFER_SIZE];
        InputStream is = bs.getInputStream();
        OutputStream os = bs.getOutputStream();

        int bytesReadFile;
        int bytesReadSpp = 0;
        for(int i = 0; i < this.fileSize; i++){
            int bytesReadBluetooth = 0;
            buf[0] = (byte)i;
            System.out.println("Writing Byte: " + (byte)i);
            os.write(buf, 0, 1);
            while(bytesReadBluetooth < 1){
                bytesReadBluetooth += is.read(buf2, 0, 1);
                System.out.println("Bytes Read: "+ bytesReadBluetooth);
            }
            bytesTrans += bytesReadBluetooth;
            System.out.println("Read Byte: " + (byte)buf2[0]);
        }

        is.close();
        os.close();
    }

    private void runClient(String addr, boolean useEl2cap) throws IOException {
        // Create a test file of size bytes
        // NOTE: Not using buffered output--likely slow performance.

        // Connect to the L2Cap server
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        BluetoothDevice bd = ba.getRemoteDevice(addr);

        //Using encrytped, authenticated channel
        BluetoothSocket bs;
        if (useEl2cap) {
            bs = bd.createEl2capSocket(L2CAP_PSM_CHANNEL);
        } else {
            bs = bd.createL2capSocket(L2CAP_PSM_CHANNEL);
        }
        bs.connect();
        System.out.println("Client Connected");

        clientLooper(bs);

        // Provide a final report
        printClientTransferStatus();

        System.exit(0);
    }

    private void printServerTransferStatus() {
        long currentTime = SystemClock.elapsedRealtime();
        double transferRate = (double)bytesTrans*1000/(double)(currentTime - transferStartTime);

        System.out.println(Double.toString(transferRate) + " bytes/sec");
    }

    private class ServerReporter extends Thread {
        @Override
        public void run() {
            while(runServerReporter) {
                try {
                    sleep(REPORTING_INTERVAL_MS);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }

                printServerTransferStatus();
            }
        }
    }

    private void serverLooper(BluetoothSocket bs) throws IOException {
        byte buf[] = new byte[BUFFER_SIZE];
        InputStream is = bs.getInputStream();
        OutputStream os = bs.getOutputStream();

        int bytesRead;
        while ((bytesRead = is.read(buf)) > 0) {
            bytesTrans = bytesTrans + bytesRead;
            System.out.println("Read and Writing byte:" + buf[0]);
            os.write(buf, 0, bytesRead);
        }

        is.close();
        os.close();
    }

    private void runServer(boolean useEl2cap) throws IOException {
        // Create a listening socket and block for a connection
        BluetoothAdapter ba = BluetoothAdapter.getDefaultAdapter();
        BluetoothServerSocket bss;

        System.out.println("Bluetooth Address: " + ba.getAddress());

        //using encrypted, authenticated channel
        if (useEl2cap) {
            bss = ba.listenUsingEl2capOn(L2CAP_PSM_CHANNEL);
        } else {
            bss = ba.listenUsingL2capOn(L2CAP_PSM_CHANNEL);
        }

        BluetoothSocket bs = bss.accept();

        // Loop everything we see
        transferStartTime = SystemClock.elapsedRealtime();
        ServerReporter reporter = new ServerReporter();
        runServerReporter = true;
        reporter.start();
        serverLooper(bs);
        runServerReporter = false;

        // Provide a final report
        printServerTransferStatus();

        System.exit(0);
    }

    public static void main(String[] args) {
        L2CapTest l2catTest = new L2CapTest();
        boolean useEl2cap = false;
        Looper.prepare();

        if (args.length < 1) {
            usage();
            System.exit(1);
        }

        if (args[0].equals("-s")) {
            if (args.length == 2 && args[1].equals("-e")) {
                useEl2cap = true;
            }

            try {
                l2catTest.runServer(useEl2cap);
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
        } else if (args[0].equals("-c")) {
            if (args.length < 3) {
                usage();
                System.exit(1);
            }

            try {
                l2catTest.fileSize = Integer.parseInt(args[2]);

                if (args.length == 4 && args[3].equals("-e")) {
                    useEl2cap = true;
                }

                l2catTest.runClient(args[1], useEl2cap);
            } catch (NumberFormatException e) {
                System.err.println("Bogus transfer size passed.");
                usage();
                System.exit(1);
            } catch (IOException e) {
                e.printStackTrace();
                System.exit(1);
            }
        } else {
            usage();
            System.exit(1);
        }
    }

    /* Print usage information */
    private static void usage() {
        System.err.println("l2cap_test -s [-e]");
        System.err.println("l2cap_test -c BD_ADDR size [-e]");
        System.err.println();
        System.err.println("\t-s: server mode");
        System.err.println("\t-c: client mode, server BD_ADDR and number of bytes to send required");
        System.err.println("\t-e: Attempt to use an 'eL2CAP' socket for the transfer");
    }
}
