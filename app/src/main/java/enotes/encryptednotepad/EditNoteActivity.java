package enotes.encryptednotepad;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.*;
import android.text.style.BackgroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SearchView;
import android.widget.TextView;
import enotes.doc.*;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class EditNoteActivity extends AppCompatActivity {

    private static final String TAG = "ENOTES";
    private static final int ID_OPEN_NOTE = 1872;
    private static final int ID_CREATE_NOTE = 1873;

    private EditText editText;
    private ScrollView scrollView;
    private DocMetadata docMeta;
    private int colorPrimary;
    private int colorAccent;
    private SearchHighlighter highlighter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_note);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        // search-related
        final TypedValue value = new TypedValue();
        getTheme().resolveAttribute(R.attr.colorAccent, value, true);
        colorAccent = value.data;
        getTheme().resolveAttribute(R.attr.colorPrimary, value, true);
        colorPrimary = value.data;
        highlighter = new SearchHighlighter();
        scrollView = (ScrollView) findViewById(R.id.scrollView);
        SearchView searchView = (SearchView) findViewById(R.id.searchView);
        searchView.setLayoutParams(new Toolbar.LayoutParams(Gravity.END));
        searchView.setOnCloseListener(new SearchView.OnCloseListener() {
            @Override
            public boolean onClose() {
                highlighter.clearHighlights();
                return false;
            }
        });
        searchView.setOnQueryTextListener(new SearchTextListener());

        // edit text
        editText = (EditText) findViewById(R.id.editText);
        editText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence charSequence, int start, int count, int after) { }

            @Override
            public void onTextChanged(CharSequence charSequence, int start, int count, int after) { }

            @Override
            public void afterTextChanged(Editable editable) {
                if (!docMeta.modified) {
                    docMeta.modified = true;
                    updateTitle();
                }
            }
        });

        // more init
        docMeta = new DocMetadata();
        updateTitle();

        // check if launched with intent
        Intent intent = getIntent();
        if (intent != null && intent.getAction().equals(Intent.ACTION_VIEW)) {
            openNote(intent.getData());
        } else {
            chooseDocument();
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.menu_edit_note, menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        switch (id) {
            case R.id.action_new:
                checkSaveAnd(new Action<Object>() {
                    @Override
                    public boolean execute(Object arg) {
                        docMeta = new DocMetadata();
                        editText.getText().clear();
                        editText.getText().clearSpans();
                        docMeta.modified = false;
                        updateTitle();
                        return true;
                    }
                });
                return true;
            case R.id.action_open:
                checkSaveAnd(new Action<Object>() {
                    @Override
                    public boolean execute(Object arg) {
                        chooseDocument();
                        return true;
                    }
                });
                return true;
            case R.id.action_save:
                saveDocument();
                return true;
            case R.id.action_save_as:
                createDocument();
                return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void chooseDocument() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(getString(R.string.mime_type_app));
        startActivityForResult(intent, ID_OPEN_NOTE);
    }

    private void saveDocument() {
        if (docMeta.key != null && docMeta.filename != null) {
            Doc doc = new Doc(editText.getText().toString(), docMeta);
            saveNote(doc);
        } else {
            createDocument();
        }
    }

    private void createDocument() {
        withPassword(getString(R.string.dialog_password_encrypt), new Action<String>() {
            @Override
            public boolean execute(String pwd) {
                docMeta.setKey(pwd);
                Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType(getString(R.string.mime_type_app));
                startActivityForResult(intent, ID_CREATE_NOTE);
                return true;
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == ID_OPEN_NOTE && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                Uri uri = data.getData();
                openNote(uri);
            }
        } else if (requestCode == ID_CREATE_NOTE && resultCode == Activity.RESULT_OK) {
            if (data != null) {
                Uri uri = data.getData();
                docMeta.filename = uri.toString();
                Doc doc = new Doc(editText.getText().toString(), docMeta);
                saveNote(doc);
            }
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private void openNote(final Uri uri) {
        withPassword(getString(R.string.dialog_password_decrypt), new Action<String>() {
            @Override
            public boolean execute(String pwd) {
                try (InputStream inputStream = getContentResolver().openInputStream(uri)) {
                    Doc doc = Doc.open(inputStream, pwd);
                    docMeta = doc.getDocMetadata();
                    docMeta.filename = uri.toString();
                    editText.setText(doc.getText(), TextView.BufferType.SPANNABLE);
                    docMeta.modified = false;
                    updateTitle();
                    return true;
                } catch (DocPasswordException ignored) {
                    // TODO: ask again?
                    Snackbar.make(editText, R.string.warn_password_invalid, Snackbar.LENGTH_LONG)
                            .setAction(android.R.string.ok, null).show();
                    return false;
                } catch (IOException | DocException e) {
                    Snackbar.make(editText, e.getMessage(), Snackbar.LENGTH_LONG)
                            .setAction(android.R.string.ok, null).show();
                    Log.e(TAG, e.getMessage(), e);
                    return false;
                }
            }
        });
    }

    private void saveNote(Doc doc) {
        try {
            OutputStream out = this.getContentResolver().openOutputStream(Uri.parse(doc.getDocMetadata().filename));
            doc.save(out);
            docMeta.modified = false;
            updateTitle();
        } catch (IOException | DocPasswordException e) {
            Snackbar.make(editText, e.getMessage(), Snackbar.LENGTH_LONG)
                    .setAction(android.R.string.ok, null).show();
            Log.e(TAG, e.getMessage(), e);
        }
    }

    private void withPassword(String message, final Action<String> action) {
        AlertDialog.Builder pwDialog = new AlertDialog.Builder(this);
        final EditText edit = new EditText(this);
        edit.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        pwDialog.setView(edit);
        pwDialog.setTitle(message);
        pwDialog.setCancelable(true);
        pwDialog.setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int i) {
                String pwd = edit.getText().toString();
                if (pwd != null && !pwd.isEmpty()) {
                    action.execute(pwd);
                }
            }
        });
        pwDialog.setNegativeButton(android.R.string.cancel, null);
        pwDialog.show();
    }

    private void checkSaveAnd(final Action<Object> action) {
        if (docMeta.modified) {
            new AlertDialog.Builder(this)
                    .setTitle(getString(R.string.dialog_save_discard))
                    .setCancelable(true)
                    .setPositiveButton(getString(R.string.option_save), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialog, int i) {
                            saveDocument();
                        }
                    })
                    .setNegativeButton(getString(R.string.option_discard), new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            action.execute(null);
                        }
                    })
                    .show();
        } else {
            action.execute(null);
        }
    }

    private interface Action<T> {
        boolean execute(T arg);
    }

    private void updateTitle() {
        String fn;
        if (docMeta.filename == null) {
            fn = getString(R.string.title_new_note);
        } else {
            String pathSegment = Uri.parse(docMeta.filename).getLastPathSegment();
            int i = pathSegment.indexOf(':');
            if (i >= 0) {
                pathSegment = pathSegment.substring(i + 1);
            }
            fn = pathSegment;
        }
        if (docMeta.modified) {
            fn += "*";
        }
        this.setTitle(fn);
    }

    public class SearchHighlighter implements Searcher.Highlighter {
        @Override
        public void clearHighlights() {
            for (SearchHighlightSpan span : editText.getText().getSpans(0, editText.getText().length(), SearchHighlightSpan.class)) {
                editText.getText().removeSpan(span);
            }
        }

        @Override
        public void addHighlight(int start, int end, boolean isCurrent) {
            editText.getText().setSpan(new SearchHighlightSpan(isCurrent ? colorAccent : colorPrimary),
                    start, end, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
    }

    private class SearchTextListener implements SearchView.OnQueryTextListener {
        private Searcher searcher;
        @Override
        public boolean onQueryTextSubmit(final String findText) {
            if (searcher != null && !findText.equalsIgnoreCase(searcher.getWord())) {
                highlighter.clearHighlights();
                searcher = null;
            }
            if (findText.isEmpty()) {
                return false;
            }
            find(findText);
            return true;
        }

        private void find(String findText) {
            if (searcher == null) {
                searcher = new Searcher(findText.toLowerCase(), editText.getText().toString().toLowerCase(),
                        editText.getSelectionStart(), highlighter);
            }
            final int found = searcher.findNext();
            if (found == -1) {
                Snackbar.make(editText, "Not found: " + findText, Snackbar.LENGTH_LONG)
                        .setAction(android.R.string.ok, null).show();
            } else {
                scrollTo(found);
            }
        }

        @Override
        public boolean onQueryTextChange(String findText) {
            if (searcher != null) {
                highlighter.clearHighlights();
                searcher = null;
            }
            if (findText.length() < 2) {
                return false;
            }
            find(findText);
            return true;
        }

        private void scrollTo(int found) {
            //editText.setSelection(found);
            Layout layout = editText.getLayout();
            int line = layout.getLineForOffset(found);
            int baseline = layout.getLineBaseline(line);
            int ascent = layout.getLineAscent(line);
            int y = baseline + ascent;
            scrollView.smoothScrollTo(0, y);
            editText.invalidate();
        }
    }

    /**
     * Custom class to allow span filtering by class type - we shouldn't remove all spans, only ours
     */
    @SuppressLint("ParcelCreator")
    private static class SearchHighlightSpan extends BackgroundColorSpan {
        SearchHighlightSpan(int color) {
            super(color);
        }
    }


/*    private SparseArray<Action> permissionRequests = new SparseArray<>();
    public void withPermission(String permission, String message, Action action) {
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, permission)) {
                Snackbar.make(editText, message, Snackbar.LENGTH_LONG).setAction(android.R.string.ok, null).show();
            }
            int reqId = new Random().nextInt(1000);
            permissionRequests.put(reqId, action);
            ActivityCompat.requestPermissions(this, new String[] { permission }, reqId);
        } else {
            action.execute(null);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        Action action = permissionRequests.get(requestCode);
        permissionRequests.remove(requestCode);
        if (action != null && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            action.execute(null);
        }
    }*/

}
