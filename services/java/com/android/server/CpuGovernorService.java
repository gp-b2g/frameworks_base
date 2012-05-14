/*
 * Copyright (c) 2011-2012, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *   * Redistributions of source code must retain the above copyright
 *     notice, this list of conditions and the following disclaimer.
 *   * Redistributions in binary form must reproduce the above copyright
 *     notice, this list of conditions and the following disclaimer in the
 *     documentation and/or other materials provided with the distribution.
 *   * Neither the name of Code Aurora nor
 *     the names of its contributors may be used to endorse or promote
 *     products derived from this software without specific prior written
 *     permission.
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

package com.android.server;

import java.io.File;
import java.io.FileReader;
import java.io.BufferedReader;
import java.io.PrintWriter;

import java.util.Vector;
import java.util.ConcurrentModificationException;

import android.os.SystemProperties;

import android.app.PendingIntent;

import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.BroadcastReceiver;
import android.text.format.Time;

import android.util.Log;

class CpuGovernorService {
    private final String TAG = "CpuGovernorService";
    private Context mContext;
    private SamplingRateChangeProcessor mSamplingRateChangeProcessor =
        new SamplingRateChangeProcessor();
    private IOBusyVoteProcessor mIOBusyVoteChangeProcessor =
        new IOBusyVoteProcessor();

    public CpuGovernorService(Context context) {
        mContext = context;
        IntentFilter intentFilter = new IntentFilter();

        intentFilter.addAction(Intent.ACTION_SCREEN_ON);
        intentFilter.addAction(Intent.ACTION_SCREEN_OFF);
        intentFilter.addAction(IOBusyVoteProcessor.ACTION_IOBUSY_VOTE);
        intentFilter.addAction(IOBusyVoteProcessor.ACTION_IOBUSY_UNVOTE);
        intentFilter.addAction(IOBusyVoteProcessor.ACTION_DOWN_FACTOR_DECREASE);
        intentFilter.addAction(IOBusyVoteProcessor.ACTION_DOWN_FACTOR_RESTORE);
        new Thread(mSamplingRateChangeProcessor).start();
        new Thread(mIOBusyVoteChangeProcessor).start();
        mContext.registerReceiver(mReceiver, intentFilter);
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            boolean changeAdded = false;

            Log.i(TAG, "intent action: " + intent.getAction());

            if (intent.getAction().equals(Intent.ACTION_SCREEN_ON)) {
                if (SystemProperties.getInt("dev.pm.dyn_samplingrate", 0) != 0) {
                    while (!changeAdded) {
                        try {
                            mSamplingRateChangeProcessor.getSamplingRateChangeRequests().
                                add(SamplingRateChangeProcessor.SAMPLING_RATE_DECREASE);
                            changeAdded = true;
                        } catch (ConcurrentModificationException concurrentModificationException) {
                            // Ignore and try again.
                        }
                    }

                    synchronized (mSamplingRateChangeProcessor.getSynchObject()) {
                        mSamplingRateChangeProcessor.getSynchObject().notify();
                        mSamplingRateChangeProcessor.setNotificationPending(true);
                    }
                }
            } else if (intent.getAction().equals(Intent.ACTION_SCREEN_OFF)) {
                if (SystemProperties.getInt("dev.pm.dyn_samplingrate", 0) != 0) {
                    while (!changeAdded) {
                        try {
                            mSamplingRateChangeProcessor.getSamplingRateChangeRequests().
                                add(SamplingRateChangeProcessor.SAMPLING_RATE_INCREASE);
                            changeAdded = true;
                        } catch (ConcurrentModificationException concurrentModificationException) {
                            // Ignore and try again.
                        }
                    }

                    synchronized (mSamplingRateChangeProcessor.getSynchObject()) {
                        mSamplingRateChangeProcessor.getSynchObject().notify();
                        mSamplingRateChangeProcessor.setNotificationPending(true);
                    }
                }
            } else if (intent.getAction().equals(IOBusyVoteProcessor.ACTION_IOBUSY_VOTE)) {
                int voteType = intent.getExtras().getInt("com.android.server.CpuGovernorService.voteType");
                Log.i(TAG, "IOBUSY vote: " + voteType);

                while (!changeAdded) {
                    try {
                        if (voteType == 1) {
                            mIOBusyVoteChangeProcessor.getIOBusyChangeRequests().
                                add(IOBusyVoteProcessor.IO_IS_BUSY_VOTE_ON);
                        } else {
                            mIOBusyVoteChangeProcessor.getIOBusyChangeRequests().
                                add(IOBusyVoteProcessor.IO_IS_BUSY_VOTE_OFF);
                        }
                        changeAdded = true;
                    } catch (ConcurrentModificationException concurrentModificationException) {
                        // Ignore and try again.
                    }
                }

                synchronized (mIOBusyVoteChangeProcessor.getSynchObject()) {
                    mIOBusyVoteChangeProcessor.getSynchObject().notify();
                    mIOBusyVoteChangeProcessor.setNotificationPending(true);
                }
            } else if (intent.getAction().equals(IOBusyVoteProcessor.ACTION_IOBUSY_UNVOTE)) {
                int voteType = intent.getExtras().getInt("com.android.server.CpuGovernorService.voteType");
                Log.i(TAG, "IOBUSY unvote: " + voteType);

                while (!changeAdded) {
                    try {
                        if (voteType == 1) {
                            mIOBusyVoteChangeProcessor.getIOBusyChangeRequests().
                                add(IOBusyVoteProcessor.IO_IS_BUSY_UNVOTE_ON);
                        } else {
                            mIOBusyVoteChangeProcessor.getIOBusyChangeRequests().
                                add(IOBusyVoteProcessor.IO_IS_BUSY_UNVOTE_OFF);
                        }
                        changeAdded = true;
                    } catch (ConcurrentModificationException concurrentModificationException) {
                        // Ignore and try again.
                    }
                }

                synchronized (mIOBusyVoteChangeProcessor.getSynchObject()) {
                    mIOBusyVoteChangeProcessor.getSynchObject().notify();
                    mIOBusyVoteChangeProcessor.setNotificationPending(true);
                }
            } else if (intent.getAction().equals(IOBusyVoteProcessor.ACTION_DOWN_FACTOR_DECREASE)) {
                Log.i(TAG, "sampling_down_factore reduced to 1");

                while (!changeAdded) {
                    try {
                            mIOBusyVoteChangeProcessor.getIOBusyChangeRequests().
                                add(IOBusyVoteProcessor.DOWN_FACTOR_DECREASE);
                        changeAdded = true;
                    } catch (ConcurrentModificationException concurrentModificationException) {
                        // Ignore and try again.
                    }
                }

                synchronized (mIOBusyVoteChangeProcessor.getSynchObject()) {
                    mIOBusyVoteChangeProcessor.getSynchObject().notify();
                    mIOBusyVoteChangeProcessor.setNotificationPending(true);
                }
            } else if (intent.getAction().equals(IOBusyVoteProcessor.ACTION_DOWN_FACTOR_RESTORE)) {
                Log.i(TAG, "sampling_down_factore restored ");

                while (!changeAdded) {
                    try {
                            mIOBusyVoteChangeProcessor.getIOBusyChangeRequests().
                                add(IOBusyVoteProcessor.DOWN_FACTOR_RESTORE);
                        changeAdded = true;
                    } catch (ConcurrentModificationException concurrentModificationException) {
                        // Ignore and try again.
                    }
                }

                synchronized (mIOBusyVoteChangeProcessor.getSynchObject()) {
                    mIOBusyVoteChangeProcessor.getSynchObject().notify();
                    mIOBusyVoteChangeProcessor.setNotificationPending(true);
                }
            }
        }
    };
}

class IOBusyVoteProcessor implements Runnable {
    private final String TAG = "IOBusyVoteProcessor";
    private static final String IO_IS_BUSY_FILE_PATH =
        "/sys/devices/system/cpu/cpufreq/ondemand/io_is_busy";
    private static final String DOWN_FACTOR_FILE_PATH =
	"/sys/devices/system/cpu/cpufreq/ondemand/sampling_down_factor";
    private final int MAX_ONDEMAND_VALUE_LENGTH = 32;
    private boolean mNotificationPending = false;
    private Vector<Integer> mIOBusyChanges = new Vector<Integer>();
    private Object mSynchIOBusyChanges = new Object();
    private int mSavedIOBusyValue = -1;
    private int mCurrentIOBusyValue = -1;
    private int mSavedDownFactorValue = -1;
    private int mCurrentDownFactorValue = -1;
    private int mOnVotes = 0;
    private int mOffVotes = 0;
    private int mDownFactorDecreaseVotes = 0;
    private boolean mError = false;

    public static final int IO_IS_BUSY_VOTE_ON = 1;
    public static final int IO_IS_BUSY_VOTE_OFF = 2;
    public static final int IO_IS_BUSY_UNVOTE_ON = 3;
    public static final int IO_IS_BUSY_UNVOTE_OFF = 4;
    public static final int DOWN_FACTOR_RESTORE = 5;
    public static final int DOWN_FACTOR_DECREASE = 6;
    public static final String ACTION_IOBUSY_VOTE = "com.android.server.CpuGovernorService.action.IOBUSY_VOTE";
    public static final String ACTION_IOBUSY_UNVOTE = "com.android.server.CpuGovernorService.action.IOBUSY_UNVOTE";
    public static final String ACTION_DOWN_FACTOR_DECREASE = "com.android.server.CpuGovernorService.action.DOWN_FACTOR_DECREASE";
    public static final String ACTION_DOWN_FACTOR_RESTORE = "com.android.server.CpuGovernorService.action.DOWN_FACTOR_RESTORE";
    public void setNotificationPending(boolean notificationPending) {
        mNotificationPending = notificationPending;
    }

    public boolean getNotificationPending() {
        return mNotificationPending;
    }

    public Vector<Integer> getIOBusyChangeRequests() {
        return mIOBusyChanges;
    }

    public Object getSynchObject() {
        return mSynchIOBusyChanges;
    }

    public void initializeIOBusyValue() {
        mSavedIOBusyValue = readOndemandFile(IO_IS_BUSY_FILE_PATH);
        mCurrentIOBusyValue = mSavedIOBusyValue;
        mSavedDownFactorValue = readOndemandFile(DOWN_FACTOR_FILE_PATH);
        mCurrentDownFactorValue = mSavedDownFactorValue;
    }

    public void run() {
        while (true && !mError) {
            try {
                synchronized (mSynchIOBusyChanges) {
                    if (!mNotificationPending) {
                        mSynchIOBusyChanges.wait();
                    }

                    mNotificationPending = false;
                }
            } catch (InterruptedException interruptedException) {
            }

            while (!mIOBusyChanges.isEmpty()) {
                try{
                    int ioBusyChangeRequestType = mIOBusyChanges.remove(0);

                    if (mOnVotes == 0 && mOffVotes == 0 && mDownFactorDecreaseVotes == 0) {
                        // There are no votes in the system. This is a good time
                        // to set the saved io_is_busy value.
                        initializeIOBusyValue();
                    }

                    if (mError) {
                        break;
                    }

                    if (ioBusyChangeRequestType == IO_IS_BUSY_VOTE_ON) {
                        voteOn();
                    } else if (ioBusyChangeRequestType == IO_IS_BUSY_VOTE_OFF) {
                        voteOff();
                    } else if (ioBusyChangeRequestType == IO_IS_BUSY_UNVOTE_ON) {
                        unvoteOn();
                    } else if (ioBusyChangeRequestType == IO_IS_BUSY_UNVOTE_OFF) {
                        unvoteOff();
                    } else if (ioBusyChangeRequestType == DOWN_FACTOR_RESTORE) {
                        downFactorRestore();
                    } else if (ioBusyChangeRequestType == DOWN_FACTOR_DECREASE) {
                        downFactorDecrease();
                    }
                } catch (ConcurrentModificationException concurrentModificationException) {
                    // Ignore and make the thread try again.
                }
            }
        }
    }

    private void downFactorDecrease() {
        mCurrentDownFactorValue = 1;
        setOndemandValue(DOWN_FACTOR_FILE_PATH,mCurrentDownFactorValue);
        mDownFactorDecreaseVotes++;
    }

    private void downFactorRestore() {
        if (mDownFactorDecreaseVotes == 0) {
            Log.e(TAG, "Down Factor votes can't be negative.");
            return;
        }
        mDownFactorDecreaseVotes--;
        if (mDownFactorDecreaseVotes == 0) {
            mCurrentDownFactorValue = mSavedDownFactorValue;
            setOndemandValue(DOWN_FACTOR_FILE_PATH,mCurrentDownFactorValue);
        }
    }

    private void voteOn() {
        mCurrentIOBusyValue = 1;
        setOndemandValue(IO_IS_BUSY_FILE_PATH,mCurrentIOBusyValue);
        mOnVotes++;
    }

    private void unvoteOn() {
        if (mOnVotes == 0) {
            Log.e(TAG, "On votes can't be negative.");

            return;
        }

        mOnVotes--;

        if (mOnVotes == 0) {
            // There are no more on votes. If there are no more
            // off votes either, we can go to the orinigal io_is_busy
            // state. Otherwise, we respect the off votes and turn
            // io_is_busy off.
            if (mOffVotes == 0) {
                mCurrentIOBusyValue = mSavedIOBusyValue;
                setOndemandValue(IO_IS_BUSY_FILE_PATH,mCurrentIOBusyValue);
            } else if (mOffVotes > 0) {
                mCurrentIOBusyValue = 0;
                setOndemandValue(IO_IS_BUSY_FILE_PATH,mCurrentIOBusyValue);
            } else {
                mError = true;

                Log.e(TAG, "Off votes can't be negative.");
            }
        }
    }

    private void voteOff() {
        if (mOnVotes == 0) {
            mCurrentIOBusyValue = 0;
            setOndemandValue(IO_IS_BUSY_FILE_PATH,mCurrentIOBusyValue);
        }

        mOffVotes++;
    }

    private void unvoteOff() {
        if (mOffVotes == 0) {
            Log.e(TAG, "Off votes can't be negative.");

            return;
        }

        mOffVotes--;

        if (mOffVotes == 0 && mOnVotes == 0) {
            mCurrentIOBusyValue = mSavedIOBusyValue;
            setOndemandValue(IO_IS_BUSY_FILE_PATH,mCurrentIOBusyValue);
        }
    }

    /*
     * Set the passed in ondemandValue as the current
     * value to the file specified in filePath
     */
    private void setOndemandValue(String filePath, int ondemandValue) {
        File fileOndemand = new File(filePath);

        if (fileOndemand.canWrite()) {
            try {
                PrintWriter ondemandWriter = new PrintWriter(fileOndemand);
                ondemandWriter.print(ondemandValue + "");
                ondemandWriter.close();
                Log.i(TAG, "Set " + filePath +  " to " + ondemandValue);
            } catch (Exception exception) {
                mError = true;

                Log.e(TAG, "Unable to write to " + filePath);
            }
        } else {
            mError = true;

            Log.e(TAG, filePath + " cannot be written to.");
        }
    }
    /*
     * Get the current value by reading the file in filePath
     */
    private int readOndemandFile( String filePath) {
        File fileOndemand = new File(filePath);
        int ondemandValue = -1;

        if (fileOndemand.canRead()) {
            try {
                BufferedReader ondemandValueReader = new BufferedReader(
                        new FileReader(fileOndemand));
                char[] ondemandContents = new char[MAX_ONDEMAND_VALUE_LENGTH];

                ondemandValueReader.read(ondemandContents, 0,
                        MAX_ONDEMAND_VALUE_LENGTH - 1);
                ondemandValueReader.close();

                try {
                    ondemandValue = Integer.parseInt((new String(ondemandContents)).trim());
                } catch (Exception exception) {
                    mError = true;

                    Log.e(TAG, "Unable to read: " + filePath + " Contents: " + new String(ondemandContents));
                }
            } catch (Exception exception) {
                mError = true;

                Log.e(TAG, filePath + " cannot be read.");
            }
        } else {
            mError = true;

            Log.e(TAG, filePath + " cannot be read.");
        }

        return ondemandValue;
    }
}

class SamplingRateChangeProcessor implements Runnable {
    private final String TAG = "SamplingRateChangeProcessor";
    private static final String SAMPLING_RATE_FILE_PATH =
        "/sys/devices/system/cpu/cpufreq/ondemand/sampling_rate";
    private static final String SCREEN_OFF_SAMPLING_RATE = "500000";
    private boolean mNotificationPending = false;
    private Vector<Integer> mSamplingRateChanges = new Vector<Integer>();
    private Object mSynchSamplingRateChanges = new Object();
    private String mSavedSamplingRate = "0";
    private int MAX_SAMPLING_RATE_LENGTH = 32;

    public static final int SAMPLING_RATE_INCREASE = 1;
    public static final int SAMPLING_RATE_DECREASE = 2;

    public void setNotificationPending(boolean notificationPending) {
        mNotificationPending = notificationPending;
    }

    public boolean getNotificationPending() {
        return mNotificationPending;
    }

    public Vector<Integer> getSamplingRateChangeRequests() {
        return mSamplingRateChanges;
    }

    public Object getSynchObject() {
        return mSynchSamplingRateChanges;
    }

    public void run() {
        while (true) {
            try {
                synchronized (mSynchSamplingRateChanges) {
                    if (!mNotificationPending) {
                        mSynchSamplingRateChanges.wait();
                    }

                    mNotificationPending = false;
                }
            } catch (InterruptedException interruptedException) {
            }

            while (!mSamplingRateChanges.isEmpty()) {
                try{
                    int samplingRateChangeRequestType = mSamplingRateChanges.remove(0);

                    if (samplingRateChangeRequestType == SAMPLING_RATE_INCREASE) {
                        increaseSamplingRate();
                    } else if (samplingRateChangeRequestType == SAMPLING_RATE_DECREASE) {
                        decreaseSamplingRate();
                    }
                } catch (ConcurrentModificationException concurrentModificationException) {
                    // Ignore and make the thread try again.
                }
            }
        }
    }

    private void increaseSamplingRate() {
        File fileSamplingRate = new File(SAMPLING_RATE_FILE_PATH);

        if (fileSamplingRate.canRead() && fileSamplingRate.canWrite()) {
            try {
                BufferedReader samplingRateReader = new BufferedReader(
                        new FileReader(fileSamplingRate));
                char[] samplingRate = new char[MAX_SAMPLING_RATE_LENGTH];

                samplingRateReader.read(samplingRate, 0,
                        MAX_SAMPLING_RATE_LENGTH - 1);
                samplingRateReader.close();

                mSavedSamplingRate = new String(samplingRate);
                PrintWriter samplingRateWriter = new PrintWriter(fileSamplingRate);

                samplingRateWriter.print(SystemProperties.get("dev.pm.dyn_sample_period", SCREEN_OFF_SAMPLING_RATE));
                samplingRateWriter.close();
                Log.i(TAG, "Increased sampling rate.");
            } catch (Exception exception) {
                mSavedSamplingRate = "0";
                Log.e(TAG, "Error occurred while increasing sampling rate: " + exception.getMessage());
            }
        }
    }

    private void decreaseSamplingRate() {
        File fileSamplingRate = new File(SAMPLING_RATE_FILE_PATH);

        if (mSavedSamplingRate.equals("0") == false && fileSamplingRate.canWrite()) {
            try {
                PrintWriter samplingRateWriter = new PrintWriter(fileSamplingRate);

                samplingRateWriter.print(mSavedSamplingRate);
                samplingRateWriter.close();
                Log.i(TAG, "Decreased sampling rate.");
            } catch (Exception exception) {
                Log.e(TAG, "Error occurred while decreasing sampling rate: " + exception.getMessage());
            }
        }
    }
}


