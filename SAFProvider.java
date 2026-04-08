package com.netease.filmlytv;

import android.webkit.MimeTypeMap;

import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Point;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract.Document;
import android.provider.DocumentsContract.Root;
import android.provider.DocumentsProvider;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;

public class SAFProvider extends DocumentsProvider {

    private static final String ALL_MIME_TYPES = "*/*";

    // --- 修改重点：获取应用的根数据目录 (/data/data/com.netease.filmlytv) ---
    private File getBaseDir() {
        // 使用 getApplicationInfo().dataDir 直接指向应用根目录
        return new File(getContext().getApplicationInfo().dataDir);
    }

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
        Root.COLUMN_ROOT_ID