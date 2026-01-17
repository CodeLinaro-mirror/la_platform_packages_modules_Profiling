/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.profiling.utils;

import android.annotation.IntDef;
import android.os.Environment;
import android.os.Handler;
import android.os.RateLimiterRecordsWrapper;
import android.util.AtomicFile;
import android.util.Log;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

public abstract class RateLimiterBase {
    private static final String TAG = RateLimiterBase.class.getSimpleName();
    protected static final boolean DEBUG = false;

    public static final int RATE_LIMIT_RESULT_ALLOWED = 0;
    public static final int RATE_LIMIT_RESULT_BLOCKED_PROCESS = 1;
    public static final int RATE_LIMIT_RESULT_BLOCKED_SYSTEM = 2;

    /** List of collections of run costs and entries from different time ranges. */
    @VisibleForTesting public List<EntryGroupWrapper> mPastRuns;

    private final HandlerCallback mHandlerCallback;

    private Runnable mPersistRunnable = null;
    private boolean mPersistScheduled = false;

    private long mLastPersistedTimestampMs;

    /**
     * The path to the directory which includes the historical rate limiter data file as specified
     * in {@link #mPersistFile}.
     */
    @VisibleForTesting public File mPersistStoreDir;

    /** The historical rate limiter data file, persisted in the storage. */
    @VisibleForTesting public File mPersistFile;

    @VisibleForTesting public AtomicBoolean mDataLoaded = new AtomicBoolean();

    @IntDef(
            value = {
                RATE_LIMIT_RESULT_ALLOWED,
                RATE_LIMIT_RESULT_BLOCKED_PROCESS,
                RATE_LIMIT_RESULT_BLOCKED_SYSTEM,
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface RateLimitResult {}

    /**
     * @param handlerCallback Callback for rate limiter to obtain a {@link Handler} to schedule work
     *     such as persisting to storage.
     */
    public RateLimiterBase(HandlerCallback handlerCallback) {
        mHandlerCallback = handlerCallback;
    }

    /**
     * Initializes rate limiter including reading previous records from disk. Must be called after
     * instance creation and before use. If rate limiter is used before this is called, it will
     * return {@link #RATE_LIMIT_RESULT_BLOCKED_SYSTEM}.
     */
    public void initialize() {
        List<TimeBucket> timeBuckets = getTimeBuckets();
        if (timeBuckets.size() == 0) {
            throw new IllegalArgumentException(
                    "Rate limiter instance needs to have at least 1 time bucket");
        }
        mPastRuns = new ArrayList<EntryGroupWrapper>(timeBuckets.size());
        long lastTimeBucketSizeMs = 0;
        for (int i = 0; i < timeBuckets.size(); i++) {
            mPastRuns.add(new EntryGroupWrapper(timeBuckets.get(i)));

            // Validate the order.
            long currentTimeBucketSizeMs = mPastRuns.get(i).mTimeRangeMs;
            if (currentTimeBucketSizeMs <= lastTimeBucketSizeMs) {
                throw new IllegalArgumentException(
                        "Time buckets must be provided with times ordered smallest to largest.");
            }
            lastTimeBucketSizeMs = currentTimeBucketSizeMs;
        }

        mLastPersistedTimestampMs = System.currentTimeMillis();

        setupFromPersistedData();
    }

    /**
     * Provide a list of time buckets to rate limit on, including a time range, max system cost, and
     * max process cost for each. The list is expected to be ordered by time range, smallest to
     * largest. Providing an incorrectly ordered list will result in a {@link
     * IllegalArgumentException} in {@link #initialize()}.
     */
    protected abstract List<TimeBucket> getTimeBuckets();

    /**
     * Obtain the directory for the persist file. This should be just the containing directory name
     * and not the entire path, which will be set by RateLimiterBase.
     */
    protected abstract String getPersistFileDir();

    protected abstract String getPersistFileName();

    protected abstract long getPersistToDiskFrequencyMs();

    /**
     * Check whether a profiling session with a specified cost is allowed to run per current rate
     * limiting restrictions. If the request is allowed, it will be stored as having run.
     */
    public @RateLimitResult int isProfilingRequestAllowed(int uid, int cost) {
        if (!mDataLoaded.get()) {
            // Requests are rejected before rate limiter data is loaded or if data load fails.
            Log.e(TAG, "Data loading in progress or failed, request denied.");
            return RATE_LIMIT_RESULT_BLOCKED_SYSTEM;
        }

        final long currentTimeMillis = System.currentTimeMillis();
        int status = RATE_LIMIT_RESULT_ALLOWED;
        for (int i = 0; i < mPastRuns.size(); i++) {
            status = mPastRuns.get(i).isProfilingAllowed(uid, cost, currentTimeMillis);
            if (status != RATE_LIMIT_RESULT_ALLOWED) {
                return status;
            }
        }

        // Request is allowed, iterate through again to record the cost and then return result.
        for (int i = 0; i < mPastRuns.size(); i++) {
            mPastRuns.get(i).add(uid, cost, currentTimeMillis);
        }
        maybePersistToDisk();
        return status;
    }

    /**
     * This method is meant to be called every time a profiling record is added to the history.
     *
     * <ul>
     *   <li>If persist frequency is set to 0, it will immediately persist the records to disk.
     *   <li>If a persist is already scheduled, it will do nothing.
     *   <li>If the last records persist occurred longer ago than the persist frequency, it will
     *       persist immediately.
     *   <li>In all other cases, it will schedule a persist event at persist frequency after the
     *       last persist event.
     * </ul>
     */
    @VisibleForTesting
    public void maybePersistToDisk() {
        if (mPersistScheduled) {
            // We're already waiting on a scheduled persist job, do nothing.
            return;
        }

        if (getPersistToDiskFrequencyMs() == 0
                || (System.currentTimeMillis() - mLastPersistedTimestampMs
                        >= getPersistToDiskFrequencyMs())) {
            // If persist frequency is 0 or if it's already been longer than persist frequency since
            // the last persist then persist immediately.
            persistToDisk();
        } else {
            // Schedule the persist job.
            if (mPersistRunnable == null) {
                mPersistRunnable =
                        new Runnable() {
                            @Override
                            public void run() {
                                persistToDisk();
                                mPersistScheduled = false;
                            }
                        };
            }
            mPersistScheduled = true;
            long persistDelay =
                    mLastPersistedTimestampMs
                            + getPersistToDiskFrequencyMs()
                            - System.currentTimeMillis();
            mHandlerCallback.obtainHandler().postDelayed(mPersistRunnable, persistDelay);
        }
    }

    /**
     * Clean up records and persist to disk.
     *
     * <p>Skips if {@link mPersistFile} is not accessible to write to.
     */
    public void persistToDisk() {
        // Check if file exists
        try {
            if (mPersistFile == null) {
                // Try again to create the necessary files.
                if (!setupPersistDir()) {
                    // No file, nowhere to save.
                    if (DEBUG) Log.d(TAG, "Failed setting up persist files so nowhere to save to.");
                    return;
                }
            }

            if (!mPersistFile.exists()) {
                // File doesn't exist, try to create it.
                mPersistFile.createNewFile();
            }
        } catch (Exception e) {
            if (DEBUG) Log.d(TAG, "Exception accessing persisted records", e);
            return;
        }

        // Persist using the longest time range entry group only as this will contain all records
        // for the smaller groups.
        EntryGroupWrapper pastRunsLongestTimeRange = mPastRuns.get(mPastRuns.size() - 1);

        // Clean up old records to reduce extraneous writes
        pastRunsLongestTimeRange.cleanUpOldRecords();

        // Generate proto for records.
        RateLimiterRecordsWrapper outerWrapper =
                RateLimiterRecordsWrapper.newBuilder()
                        .setRecords(pastRunsLongestTimeRange.toProto())
                        .build();

        // Write to disk
        byte[] protoBytes = outerWrapper.toByteArray();
        AtomicFile persistFile = new AtomicFile(mPersistFile);
        FileOutputStream out = null;
        try {
            out = persistFile.startWrite();
            out.write(protoBytes);
            persistFile.finishWrite(out);
        } catch (IOException e) {
            if (DEBUG) Log.d(TAG, "Exception writing records", e);
            persistFile.failWrite(out);
        }
    }

    /**
     * Load initial records data from disk and marks rate limiter ready to use if it's in an
     * acceptable state.
     */
    @VisibleForTesting
    public void setupFromPersistedData() {
        // Setup persist files
        try {
            if (!setupPersistDir()) {
                // If setup directory and file was unsuccessful then we won't be able to persist
                // records, return and leave feature disabled entirely.
                if (DEBUG) Log.d(TAG, "Failed to setup persist directory/files. Feature disabled.");
                mDataLoaded.set(false);
                return;
            }
        } catch (SecurityException e) {
            // Can't access files.
            if (DEBUG) Log.d(TAG, "Failed to setup persist directory/files. Feature disabled.", e);
            mDataLoaded.set(false);
            return;
        }

        // Check if file exists
        try {
            if (!mPersistFile.exists()) {
                // No file, nothing to load. This is an expected state for before the feature has
                // ever been used so mark ready to use and return.
                if (DEBUG) Log.d(TAG, "Persist file does not exist, skipping load from disk.");
                mDataLoaded.set(true);
                return;
            }
        } catch (SecurityException e) {
            // Can't access file.
            if (DEBUG) Log.d(TAG, "Exception accessing persist file", e);
            mDataLoaded.set(false);
            return;
        }

        // Read the file
        AtomicFile persistFile = new AtomicFile(mPersistFile);
        byte[] bytes;
        try {
            bytes = persistFile.readFully();
        } catch (IOException e) {
            if (DEBUG) Log.d(TAG, "Exception reading persist file", e);
            // We already handled no file case above and empty file would not result in exception
            // so this is a problem reading the file. Attempt remediation.
            if (handleBadFile()) {
                // Successfully remediated bad state! Mark ready to use.
                mDataLoaded.set(true);
            } else {
                // Failed to remediate bad state. Feature disabled.
                mDataLoaded.set(false);
            }
            // Return either way as {@link handleBadFile} handles the entirety of remediating the
            // bad state and the remainder of this method is no longer applicable.
            return;
        }
        if (bytes.length == 0) {
            // Empty file, nothing to load. This is an expected state for before the feature
            // persists so mark ready to use and return.
            if (DEBUG) Log.d(TAG, "Persist file is empty, skipping load from disk.");
            mDataLoaded.set(true);
            return;
        }

        // Parse file bytes to proto
        RateLimiterRecordsWrapper outerWrapper;
        try {
            outerWrapper = RateLimiterRecordsWrapper.parseFrom(bytes);
        } catch (Exception e) {
            // Failed to parse. Attempt remediation.
            if (DEBUG) Log.d(TAG, "Error parsing proto from persisted bytes", e);
            if (handleBadFile()) {
                // Successfully remediated bad state! Mark ready to use.
                mDataLoaded.set(true);
            } else {
                // Failed to remediate bad state. Feature disabled.
                mDataLoaded.set(false);
            }
            // Return either way as {@link handleBadFile} handles the entirety of remediating the
            // bad state and the remainder of this method is no longer applicable.
            return;
        }

        // Populate in memory records stores
        RateLimiterRecordsWrapper.EntryGroupWrapper weekGroupWrapper = outerWrapper.getRecords();
        final long currentTimeMillis = System.currentTimeMillis();
        for (int i = 0; i < weekGroupWrapper.getEntriesCount(); i++) {
            RateLimiterRecordsWrapper.EntryGroupWrapper.Entry entry =
                    weekGroupWrapper.getEntries(i);
            // Check if this timestamp fits the time range for each records collection.
            for (int j = 0; j < mPastRuns.size(); j++) {
                EntryGroupWrapper pastRuns = mPastRuns.get(j);
                if (entry.getTimestamp() > currentTimeMillis - pastRuns.mTimeRangeMs) {
                    pastRuns.add(entry.getUid(), entry.getCost(), entry.getTimestamp());
                }
            }
        }

        // Success!
        mDataLoaded.set(true);
    }

    /**
     * Handle a bad persist file - this can be a file that can't be read or can't be parsed.
     *
     * <p>This case is handled by attempting to delete and recreate the persist file. If this is
     * successful, it adds some fake records to make up for potentially lost records.
     *
     * <p>If the bad file is successfully remediated then RateLimiter is ready to use and no further
     * initialization is needed.
     *
     * @return whether the bad file state has been successfully remediated.
     */
    @VisibleForTesting
    public boolean handleBadFile() {
        if (mPersistFile == null) {
            // This should not happen, if there is no file how can it have been determined to be
            // bad?
            if (DEBUG) Log.d(TAG, "Attempted to remediate a bad file but the file doesn't exist.");
            return false;
        }

        try {
            // Delete the bad file, we won't likely have better luck reading it a second time.
            mPersistFile.delete();
            if (DEBUG) Log.d(TAG, "Deleted persist file which could not be parsed.");
        } catch (SecurityException e) {
            // Can't delete file so we can't recover from this state.
            if (DEBUG) Log.d(TAG, "Failed to delete persist file", e);
            return false;
        }

        try {
            if (!setupPersistDir()) {
                // If setup files was unsuccessful then we won't be able to persist files.
                if (DEBUG) Log.d(TAG, "Failed to setup persist directory/files. Feature disabled.");
                return false;
            }
            mPersistFile.createNewFile();
            if (!mPersistFile.exists()) {
                // If creating the file failed then we won't be able to persist.
                if (DEBUG) Log.d(TAG, "Failed to create persist file. Feature disabled.");
                return false;
            }
        } catch (SecurityException | IOException e) {
            // Can't access/setup files.
            if (DEBUG) Log.d(TAG, "Failed to setup persist directory/files. Feature disabled.", e);
            return false;
        }

        // If we made it this far then we have successfully deleted the bad file and created a new
        // useable one - the feature is now ready to be used!
        // However, we may have lost some records from the bad file, so add some fake records for
        // the current time with a very high cost, this effectively disables the feature for the
        // duration of rate limiting (1 week) to err on the cautious side regarding the potentially
        // lost records.
        final long timestamp = System.currentTimeMillis();
        for (int i = 0; i < mPastRuns.size(); i++) {
            mPastRuns.get(i).add(-1 /*fake uid*/, Integer.MAX_VALUE, timestamp);
        }

        // Now persist the fake records.
        maybePersistToDisk();

        // Finally, return true as we successfully remediated the bad file state.
        return true;
    }

    /**
     * Update the max system and/or process costs for each time bucket. List of time buckets
     * provided here is expected to match the one provided by {@link #getTimeBuckets()} by having
     * the same number of entries, the same values for mTimeBucketLengthMs, and the same order
     * within the list - only the cost values may differ. Cost values of <= 0 will result in the
     * previous value being retained.
     */
    public void maybeUpdateMaxCosts(List<TimeBucket> timeBuckets) {
        if (timeBuckets.size() != mPastRuns.size()) {
            return;
        }

        for (int i = 0; i < timeBuckets.size(); i++) {
            EntryGroupWrapper pastRuns = mPastRuns.get(i);
            TimeBucket timeBucket = timeBuckets.get(i);
            if (pastRuns.mTimeRangeMs == timeBucket.mTimeBucketLengthMs) {
                pastRuns.maybeUpdateMaxCosts(timeBucket.mMaxCostSystem, timeBucket.mMaxCostProcess);
            }
        }
    }

    /**
     * Create the directory and initialize the file variable for persisting records.
     *
     * @return Whether the files were successfully created.
     */
    @VisibleForTesting
    public boolean setupPersistDir() throws SecurityException {
        File dataDir = Environment.getDataDirectory();
        File systemDir = new File(dataDir, "system");
        mPersistStoreDir = new File(systemDir, getPersistFileDir());
        if (createDir(mPersistStoreDir)) {
            mPersistFile = new File(mPersistStoreDir, getPersistFileName());
            return true;
        }
        return false;
    }

    private static boolean createDir(File dir) throws SecurityException {
        if (dir.mkdir()) {
            return true;
        }

        if (dir.exists()) {
            return dir.isDirectory();
        }

        return false;
    }

    public static final class EntryGroupWrapper {
        private final Object mLock = new Object();

        @GuardedBy("mLock")
        final Queue<CollectionEntry> mEntries;

        // uid indexed
        final SparseIntArray mPerUidCost;
        final long mTimeRangeMs;

        int mMaxCost;
        int mMaxCostPerUid;
        int mTotalCost;

        EntryGroupWrapper(TimeBucket timeBucket) {
            synchronized (mLock) {
                mMaxCost = timeBucket.mMaxCostSystem;
                mMaxCostPerUid = timeBucket.mMaxCostProcess;
                mTimeRangeMs = timeBucket.mTimeBucketLengthMs;
                mEntries = new ArrayDeque<>();
                mPerUidCost = new SparseIntArray();
            }
        }

        /** Update max per system and process costs if values are valid (>=0). */
        public void maybeUpdateMaxCosts(int maxCost, int maxPerUidCost) {
            synchronized (mLock) {
                if (maxCost >= 0) {
                    mMaxCost = maxCost;
                }
                if (maxPerUidCost >= 0) {
                    mMaxCostPerUid = maxPerUidCost;
                }
            }
        }

        /** Add a record and update cached costs accordingly. */
        public void add(final int uid, final int cost, final long timestamp) {
            synchronized (mLock) {
                mTotalCost += cost;
                final int index = mPerUidCost.indexOfKey(uid);
                if (index < 0) {
                    mPerUidCost.put(uid, cost);
                } else {
                    mPerUidCost.put(uid, mPerUidCost.valueAt(index) + cost);
                }
                mEntries.offer(new CollectionEntry(uid, cost, timestamp));
            }
        }

        /** Clean up the queue by removing entries that are too old. */
        public void cleanUpOldRecords() {
            removeOlderThan(System.currentTimeMillis() - mTimeRangeMs);
        }

        /**
         * Clean up the queue by removing entries that are too old.
         *
         * @param olderThanTimestamp timestamp to remove record which are older than.
         */
        public void removeOlderThan(final long olderThanTimestamp) {
            synchronized (mLock) {
                while (mEntries.peek() != null
                        && mEntries.peek().mTimestamp <= olderThanTimestamp) {
                    final CollectionEntry entry = mEntries.poll();
                    if (entry == null) {
                        return;
                    }
                    mTotalCost -= entry.mCost;
                    if (mTotalCost < 0) {
                        mTotalCost = 0;
                    }
                    final int index = mPerUidCost.indexOfKey(entry.mUid);
                    if (index >= 0) {
                        mPerUidCost.setValueAt(
                                index, Math.max(0, mPerUidCost.valueAt(index) - entry.mCost));
                    }
                }
            }
        }

        /**
         * Check if the requested profiling is allowed by the limits of this collection after
         * ensuring the collection is up to date.
         *
         * @param uid of requesting process
         * @param cost calculated perf cost of running this query
         * @param currentTimeMillis cache time and keep consistent across checks
         * @return status indicating whether request is allowed, or which rate limiting applied to
         *     deny it.
         */
        @RateLimitResult
        int isProfilingAllowed(final int uid, final int cost, final long currentTimeMillis) {
            synchronized (mLock) {
                removeOlderThan(currentTimeMillis - mTimeRangeMs);
                if (mTotalCost + cost > mMaxCost) {
                    return RATE_LIMIT_RESULT_BLOCKED_SYSTEM;
                }
                if (mPerUidCost.get(uid, 0) + cost > mMaxCostPerUid) {
                    return RATE_LIMIT_RESULT_BLOCKED_PROCESS;
                }
                return RATE_LIMIT_RESULT_ALLOWED;
            }
        }

        RateLimiterRecordsWrapper.EntryGroupWrapper toProto() {
            synchronized (mLock) {
                RateLimiterRecordsWrapper.EntryGroupWrapper.Builder builder =
                        RateLimiterRecordsWrapper.EntryGroupWrapper.newBuilder();

                CollectionEntry[] entries = mEntries.toArray(new CollectionEntry[mEntries.size()]);
                for (int i = 0; i < entries.length; i++) {
                    builder.addEntries(entries[i].toProto());
                }

                return builder.build();
            }
        }

        void populateFromProto(RateLimiterRecordsWrapper.EntryGroupWrapper group) {
            synchronized (mLock) {
                final long currentTimeMillis = System.currentTimeMillis();
                for (int i = 0; i < group.getEntriesCount(); i++) {
                    RateLimiterRecordsWrapper.EntryGroupWrapper.Entry entry = group.getEntries(i);
                    if (entry.getTimestamp() > currentTimeMillis - mTimeRangeMs) {
                        add(entry.getUid(), entry.getCost(), entry.getTimestamp());
                    }
                }
            }
        }

        /** Get a copied array of the backing data. */
        public CollectionEntry[] getEntriesCopy() {
            synchronized (mLock) {
                CollectionEntry[] array = new CollectionEntry[mEntries.size()];
                array = mEntries.toArray(array);
                return array.clone();
            }
        }
    }

    public static final class CollectionEntry {
        public final int mUid;
        public final int mCost;
        public final Long mTimestamp;

        CollectionEntry(final int uid, final int cost, final Long timestamp) {
            mUid = uid;
            mCost = cost;
            mTimestamp = timestamp;
        }

        RateLimiterRecordsWrapper.EntryGroupWrapper.Entry toProto() {
            return RateLimiterRecordsWrapper.EntryGroupWrapper.Entry.newBuilder()
                    .setUid(mUid)
                    .setCost(mCost)
                    .setTimestamp(mTimestamp)
                    .build();
        }
    }

    public static final class TimeBucket {
        final long mTimeBucketLengthMs;
        final int mMaxCostSystem;
        final int mMaxCostProcess;

        public TimeBucket(long timeBucketLengthMs, int maxCostSystem, int maxCostProcess) {
            mTimeBucketLengthMs = timeBucketLengthMs;
            mMaxCostSystem = maxCostSystem;
            mMaxCostProcess = maxCostProcess;
        }
    }

    public interface HandlerCallback {

        /** Obtain a handler to schedule persisting records to disk. */
        Handler obtainHandler();
    }
}
