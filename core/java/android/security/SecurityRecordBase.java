/**
 * Copyright (C) 2012, Code Aurora Forum. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *     * Redistributions of source code must retain the above copyright
 *       notice, this list of conditions and the following disclaimer.
 *     * Redistributions in binary form must reproduce the above
 *       copyright notice, this list of conditions and the following
 *       disclaimer in the documentation and/or other materials provided
 *       with the distribution.
 *     * Neither the name of Code Aurora Forum, Inc. nor the names of its
 *       contributors may be used to endorse or promote products derived
 *       from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED "AS IS" AND ANY EXPRESS OR IMPLIED
 * WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED WARRANTIES OF
 * MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NON-INFRINGEMENT
 * ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS
 * BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR
 * CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF
 * SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR
 * BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY,
 * WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE
 * OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN
 * IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package android.security;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class SecurityRecordBase {
    protected HashMap<String, ArrayList<String>> mRecords;

    private SQLiteOpenHelper mOpenHelper;

    private String mTable;
    private String mColumnKey;
    private String mColumnValue;

    public SecurityRecordBase(SQLiteOpenHelper openHelper,
            String table, String columnKey, String columnValue) {
        mRecords = new HashMap<String, ArrayList<String>>();
        mOpenHelper = openHelper;
        mTable = table;
        mColumnKey = columnKey;
        mColumnValue = columnValue;

        loadDatabase();
    }

    protected void loadDatabase() {
        SQLiteDatabase db = mOpenHelper.getReadableDatabase();
        String[] columns = { mColumnKey, mColumnValue };
        Cursor c = db.query(mTable, columns, null, null, null, null, null);
        for (c.moveToFirst(); !c.isAfterLast(); c.moveToNext()) {
            String key = c.getString(0);
            String value = c.getString(1);
            addToCache(key, value);
        }
        c.close();
    }

    private boolean addToCache(String key, String value) {
        ArrayList<String> list = mRecords.get(key);
        if (list == null) {
            list = new ArrayList<String>();
            list.add(value);
            mRecords.put(key, list);
            return true;
        } else {
            if (!list.contains(value)) {
                list.add(value);
                return true;
            }
            return false;
        }
    }

    private boolean removeFromCache(String key, String value) {
        ArrayList<String> list = mRecords.get(key);
        if (list != null) {
            if (value == null) {
                list.clear();
                mRecords.remove(key);
            } else {
                if (list.contains(value)) {
                    list.remove(value);
                    if (list.isEmpty())
                        mRecords.remove(key);
                } else {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private void removeAllFromCache() {
        mRecords.clear();
    }

    private boolean insertRecord(String key, String value) {
        try {
            SQLiteDatabase db = mOpenHelper.getWritableDatabase();
            ContentValues v = new ContentValues();
            v.put(mColumnKey, key);
            v.put(mColumnValue, value);

            return (db.insert(mTable, mColumnKey, v) != -1);
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean deleteRecord(String key, String value) {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();

        String where = mColumnKey + " = ?";
        if (value != null) {
            where += " AND " + mColumnValue + " = ?";
            String[] args = { key, value };
            return (db.delete(mTable, where, args) != 0);
        } else {
            String[] args = { key };
            return (db.delete(mTable, where, args) != 0);
        }
    }

    private void removeAllRecords() {
        SQLiteDatabase db = mOpenHelper.getWritableDatabase();
        db.execSQL("delete from " + mTable);
    }

    public boolean add(String key, String value) {
        if (addToCache(key, value)) {
            return insertRecord(key, value);
        }
        return false;
    }

    public boolean remove(String key, String value) {
        if (removeFromCache(key, value)) {
            return deleteRecord(key, value);
        }
        return false;
    }

    public boolean contains(String key, String value) {
        ArrayList<String> list = mRecords.get(key);
        if (list == null)
            return false;
        return list.contains(value);
    }

    public boolean removeKey(String key) {
        return remove(key, null);
    }

    public void removeAll() {
        removeAllFromCache();
        removeAllRecords();
    }

    public interface Callback {
        public void apply(String key, String value);
    }

    public void forEach(Callback callback) {
        for (Map.Entry<String, ArrayList<String>> entry : mRecords.entrySet()) {
            String key = entry.getKey();
            for (String value : entry.getValue()) {
                callback.apply(key, value);
            }
        }
    }

    public void forKey(String key, Callback callback) {
        ArrayList<String> list = mRecords.get(key);
        if (list != null) {
            for (String value : list) {
                callback.apply(key, value);
            }
        }
    }
}
