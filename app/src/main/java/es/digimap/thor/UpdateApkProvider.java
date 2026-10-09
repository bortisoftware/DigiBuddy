package es.digimap.thor;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;

public final class UpdateApkProvider extends ContentProvider {
  @Override
  public boolean onCreate() {
    return true;
  }

  private File resolve(Uri uri) throws FileNotFoundException {
    if (!"content".equals(uri.getScheme())
        || !(getContext().getPackageName() + ".updates").equals(uri.getAuthority())
        || uri.getQuery() != null
        || uri.getFragment() != null
        || uri.getPath() == null
        || !uri.getPath().matches("/apk/[0-9a-f]{64}\\.apk")) throw new FileNotFoundException();
    return new File(new File(getContext().getCacheDir(), "updates"), uri.getLastPathSegment());
  }

  @Override
  public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
    if (!"r".equals(mode)) throw new FileNotFoundException();
    return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
  }

  @Override
  public String getType(Uri uri) {
    return "application/vnd.android.package-archive";
  }

  @Override
  public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
    try {
      File file = resolve(uri);
      String[] columns =
          projection == null
              ? new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE}
              : projection;
      MatrixCursor cursor = new MatrixCursor(columns);
      Object[] values = new Object[columns.length];
      for (int index = 0; index < columns.length; index++) {
        if (OpenableColumns.DISPLAY_NAME.equals(columns[index])) values[index] = "DigiBuddy.apk";
        if (OpenableColumns.SIZE.equals(columns[index])) values[index] = file.length();
      }
      cursor.addRow(values);
      return cursor;
    } catch (FileNotFoundException failure) {
      return null;
    }
  }

  @Override
  public Uri insert(Uri uri, ContentValues values) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int update(Uri uri, ContentValues values, String where, String[] args) {
    throw new UnsupportedOperationException();
  }

  @Override
  public int delete(Uri uri, String where, String[] args) {
    throw new UnsupportedOperationException();
  }
}
