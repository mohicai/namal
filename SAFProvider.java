package com.github.tvbox.osc;

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

    // --- 修改重点：获取应用的根数据目录 (/data/data/com.github.tvbox.osc) ---
    private File getBaseDir() {
        // 使用 getApplicationInfo().dataDir 直接指向应用根目录
        return new File(getContext().getApplicationInfo().dataDir);
    }

    private static final String[] DEFAULT_ROOT_PROJECTION = new String[]{
        Root.COLUMN_ROOT_ID,
        Root.COLUMN_MIME_TYPES,
        Root.COLUMN_FLAGS,
        Root.COLUMN_ICON,
        Root.COLUMN_TITLE,
        Root.COLUMN_SUMMARY,
        Root.COLUMN_DOCUMENT_ID,
        Root.COLUMN_AVAILABLE_BYTES
    };

    private static final String[] DEFAULT_DOCUMENT_PROJECTION = new String[]{
        Document.COLUMN_DOCUMENT_ID,
        Document.COLUMN_MIME_TYPE,
        Document.COLUMN_DISPLAY_NAME,
        Document.COLUMN_LAST_MODIFIED,
        Document.COLUMN_FLAGS,
        Document.COLUMN_SIZE
    };

    @Override
    public boolean onCreate() {
        return true;
    }

    @Override
    public Cursor queryRoots(String[] projection) {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_ROOT_PROJECTION);
        File baseDir = getBaseDir();

        final MatrixCursor.RowBuilder row = result.newRow();
        // Root ID 使用根目录路径
        row.add(Root.COLUMN_ROOT_ID, getDocIdForFile(baseDir));
        row.add(Root.COLUMN_DOCUMENT_ID, getDocIdForFile(baseDir));
        row.add(Root.COLUMN_SUMMARY, "ClashMi Root Storage");
        // 确保勾选 SUPPORTS_CREATE，方便 Termux 使用 termux-saf-create
        row.add(Root.COLUMN_FLAGS, Root.FLAG_SUPPORTS_CREATE | Root.FLAG_SUPPORTS_SEARCH | Root.FLAG_SUPPORTS_IS_CHILD);
        row.add(Root.COLUMN_TITLE, "ClashMi");
        row.add(Root.COLUMN_MIME_TYPES, ALL_MIME_TYPES);
        row.add(Root.COLUMN_AVAILABLE_BYTES, baseDir.getFreeSpace());
        row.add(Root.COLUMN_ICON, android.R.drawable.ic_menu_save);
        return result;
    }
    
    // 辅助方法：将文件转为 DocId (即绝对路径)
    private static String getDocIdForFile(File file) {
        return file.getAbsolutePath();
    }


    @Override
    public Cursor queryDocument(String documentId, String[] projection) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        includeFile(result, documentId, null);
        return result;
    }

    @Override
    public Cursor queryChildDocuments(String parentDocumentId, String[] projection, String sortOrder) throws FileNotFoundException {
        final MatrixCursor result = new MatrixCursor(projection != null ? projection : DEFAULT_DOCUMENT_PROJECTION);
        final File parent = getFileForDocId(parentDocumentId);
        File[] files = parent.listFiles();
        if (files != null) {
            for (File file : files) {
                includeFile(result, null, file);
            }
        }
        return result;
    }

    // --- 写入核心：处理文件打开请求 ---
    @Override
    public ParcelFileDescriptor openDocument(final String documentId, String mode, CancellationSignal signal) throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        // parseMode 会根据 Termux 传来的 "w" 或 "r" 自动处理权限
        final int accessMode = ParcelFileDescriptor.parseMode(mode);
        return ParcelFileDescriptor.open(file, accessMode);
    }

    @Override
    public AssetFileDescriptor openDocumentThumbnail(String documentId, Point sizeHint, CancellationSignal signal) throws FileNotFoundException {
        final File file = getFileForDocId(documentId);
        final ParcelFileDescriptor pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY);
        return new AssetFileDescriptor(pfd, 0, file.length());
    }

    // --- 创建核心：支持 Termux 创建新文件 ---
    @Override
    public String createDocument(String parentDocumentId, String mimeType, String displayName) throws FileNotFoundException {
        File parent = getFileForDocId(parentDocumentId);
        File newFile = new File(parent, displayName);
        
        // 自动处理重名冲突
        int noConflictId = 2;
        while (newFile.exists()) {
            newFile = new File(parent, displayName + " (" + noConflictId++ + ")");
        }
        
        try {
            boolean succeeded;
            if (Document.MIME_TYPE_DIR.equals(mimeType)) {
                succeeded = newFile.mkdir();
            } else {
                succeeded = newFile.createNewFile();
            }
            if (!succeeded) {
                throw new FileNotFoundException("Failed to create: " + newFile.getPath());
            }
        } catch (IOException e) {
            throw new FileNotFoundException("IO Error: " + newFile.getPath());
        }
        return getDocIdForFile(newFile);
    }

    @Override
    public void deleteDocument(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        if (!file.delete()) {
            throw new FileNotFoundException("Failed to delete: " + documentId);
        }
    }

    @Override
    public String getDocumentType(String documentId) throws FileNotFoundException {
        File file = getFileForDocId(documentId);
        if (file.isDirectory()) return Document.MIME_TYPE_DIR;
        
        // 自动识别 MIME 类型（如 .yaml, .conf）
        final int lastDot = file.getName().lastIndexOf('.');
        if (lastDot >= 0) {
            final String extension = file.getName().substring(lastDot + 1).toLowerCase();
            final String mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(extension);
            if (mime != null) return mime;
        }
        return "application/octet-stream";
    }

    @Override
    public boolean isChildDocument(String parentDocumentId, String documentId) {
        return documentId.startsWith(parentDocumentId);
    }

    // 辅助方法：将 File 对象包装进 Cursor 行
    private void includeFile(MatrixCursor result, String docId, File file) throws FileNotFoundException {
        if (docId == null) {
            docId = getDocIdForFile(file);
        } else {
            file = getFileForDocId(docId);
        }

        int flags = 0;
        if (file.isDirectory()) {
            if (file.canWrite()) flags |= Document.FLAG_DIR_SUPPORTS_CREATE;
        } else if (file.canWrite()) {
            flags |= Document.FLAG_SUPPORTS_WRITE;
            flags |= Document.FLAG_SUPPORTS_DELETE;
        }

        final MatrixCursor.RowBuilder row = result.newRow();
        row.add(Document.COLUMN_DOCUMENT_ID, docId);
        row.add(Document.COLUMN_DISPLAY_NAME, file.getName());
        row.add(Document.COLUMN_SIZE, file.length());
        row.add(Document.COLUMN_MIME_TYPE, getDocumentType(docId));
        row.add(Document.COLUMN_LAST_MODIFIED, file.lastModified());
        row.add(Document.COLUMN_FLAGS, flags);
        row.add(Document.COLUMN_ICON, android.R.drawable.ic_menu_edit);
    }

    private static File getFileForDocId(String docId) throws FileNotFoundException {
        final File f = new File(docId);
        if (!f.exists()) throw new FileNotFoundException(f.getAbsolutePath() + " not found");
        return f;
    }
}

