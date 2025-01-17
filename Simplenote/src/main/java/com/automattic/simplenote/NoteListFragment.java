package com.automattic.simplenote;

import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.sqlite.SQLiteException;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.TextUtils;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.TextAppearanceSpan;
import android.util.Log;
import android.util.SparseBooleanArray;
import android.util.TypedValue;
import android.view.ActionMode;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.CursorAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.ListFragment;
import androidx.preference.PreferenceManager;

import com.automattic.simplenote.analytics.AnalyticsTracker;
import com.automattic.simplenote.models.Note;
import com.automattic.simplenote.utils.ChecklistUtils;
import com.automattic.simplenote.utils.DateTimeUtils;
import com.automattic.simplenote.utils.DisplayUtils;
import com.automattic.simplenote.utils.DrawableUtils;
import com.automattic.simplenote.utils.HtmlCompat;
import com.automattic.simplenote.utils.PrefUtils;
import com.automattic.simplenote.utils.SearchSnippetFormatter;
import com.automattic.simplenote.utils.SearchTokenizer;
import com.automattic.simplenote.utils.StrUtils;
import com.automattic.simplenote.utils.TextHighlighter;
import com.automattic.simplenote.utils.ThemeUtils;
import com.automattic.simplenote.utils.WidgetUtils;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.simperium.client.Bucket;
import com.simperium.client.Bucket.ObjectCursor;
import com.simperium.client.Query;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.automattic.simplenote.models.Note.TAGS_PROPERTY;
import static com.automattic.simplenote.utils.PrefUtils.ALPHABETICAL_ASCENDING;
import static com.automattic.simplenote.utils.PrefUtils.ALPHABETICAL_DESCENDING;
import static com.automattic.simplenote.utils.PrefUtils.DATE_CREATED_ASCENDING;
import static com.automattic.simplenote.utils.PrefUtils.DATE_CREATED_DESCENDING;
import static com.automattic.simplenote.utils.PrefUtils.DATE_MODIFIED_ASCENDING;
import static com.automattic.simplenote.utils.PrefUtils.DATE_MODIFIED_DESCENDING;

/**
 * A list fragment representing a list of Notes. This fragment also supports
 * tablet devices by allowing list items to be given an 'activated' state upon
 * selection. This helps indicate which item is currently being viewed in a
 * {@link NoteEditorFragment}.
 * <p>
 * Activities containing this fragment MUST implement the {@link Callbacks}
 * interface.
 */
public class NoteListFragment extends ListFragment implements AdapterView.OnItemLongClickListener, AbsListView.MultiChoiceModeListener {

    /**
     * The preferences key representing the activated item position. Only used on tablets.
     */
    private static final String STATE_ACTIVATED_POSITION = "activated_position";
    private static final int POPUP_MENU_FIRST_ITEM_POSITION = 0;
    public static final String ACTION_NEW_NOTE = "com.automattic.simplenote.NEW_NOTE";
    /**
     * A dummy implementation of the {@link Callbacks} interface that does
     * nothing. Used only when this fragment is not attached to an activity.
     */
    private static Callbacks sCallbacks = new Callbacks() {
        @Override
        public void onActionModeCreated() {
        }

        @Override
        public void onActionModeDestroyed() {
        }

        @Override
        public void onNoteSelected(String noteID, int position, String matchOffsets, boolean isMarkdownEnabled, boolean isPreviewEnabled) {
        }
    };
    protected NotesCursorAdapter mNotesAdapter;
    protected String mSearchString;
    private ActionMode mActionMode;
    private View mRootView;
    private TextView mEmptyListTextView;
    private View mDividerLine;
    private FloatingActionButton mFloatingActionButton;
    private boolean mIsCondensedNoteList;
    private boolean mIsSearching;
    private ImageView mSortDirection;
    private RelativeLayout mSortLayoutContent;
    private SharedPreferences mPreferences;
    private String mSelectedNoteId;
    private TextView mSortOrder;
    private refreshListTask mRefreshListTask;
    private refreshListForSearchTask mRefreshListForSearchTask;
    private int mPreferenceSortOrder;
    private int mTitleFontSize;
    private int mPreviewFontSize;
    private boolean mIsSortDown;
    /**
     * The fragment's current callback object, which is notified of list item
     * clicks.
     */
    private Callbacks mCallbacks = sCallbacks;
    /**
     * The current activated item position. Only used on tablets.
     */
    private int mActivatedPosition = ListView.INVALID_POSITION;

    /**
     * Mandatory empty constructor for the fragment manager to instantiate the
     * fragment (e.g. upon screen orientation changes).
     */
    public NoteListFragment() {
    }

    public void setEmptyListViewClickable(boolean isClickable) {
        if (mEmptyListTextView != null) {
            mEmptyListTextView.setClickable(isClickable);
        }
    }

    @Override
    public boolean onItemLongClick(AdapterView<?> adapterView, View view, int position, long l) {
        getListView().setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        getListView().setItemChecked(position, true);
        if (mActionMode == null)
            requireActivity().startActionMode(this);
        return true;
    }

    @Override
    public boolean onCreateActionMode(ActionMode actionMode, Menu menu) {
        mCallbacks.onActionModeCreated();
        MenuInflater inflater = actionMode.getMenuInflater();
        inflater.inflate(R.menu.bulk_edit, menu);
        DrawableUtils.tintMenuWithAttribute(getActivity(), menu, R.attr.actionModeTextColor);
        mActionMode = actionMode;
        int colorResId = ThemeUtils.isLightTheme(requireContext()) ? R.color.background_light : R.color.background_dark;
        requireActivity().getWindow().setStatusBarColor(getResources().getColor(colorResId, requireActivity().getTheme()));
        return true;
    }

    @Override
    public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
        return false;
    }

    @Override
    public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
        if (getListView().getCheckedItemIds().length > 0) {

            switch (item.getItemId()) {
                case R.id.menu_delete:
                    new trashNotesTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    break;
                case R.id.menu_pin:
                    new pinNotesTask().executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
                    break;
            }
        }
        return false;
    }

    @Override
    public void onDestroyActionMode(ActionMode mode) {
        mCallbacks.onActionModeDestroyed();
        mActionMode = null;
        new Handler().postDelayed(
            new Runnable() {
                @Override
                public void run() {
                    if (getActivity() != null) {
                        NotesActivity notesActivity = (NotesActivity) getActivity();
                        setActivateOnItemClick(DisplayUtils.isLargeScreenLandscape(notesActivity));
                        notesActivity.showDetailPlaceholder();
                    }

                    requireActivity().getWindow().setStatusBarColor(getResources().getColor(android.R.color.transparent, requireActivity().getTheme()));
                }
            },
            requireContext().getResources().getInteger(android.R.integer.config_mediumAnimTime)
        );
    }

    @Override
    public void onItemCheckedStateChanged(ActionMode actionMode, int position, long id, boolean checked) {
        int checkedCount = getListView().getCheckedItemCount();
        if (checkedCount == 0)
            actionMode.setTitle("");
        else
            actionMode.setTitle(getResources().getQuantityString(R.plurals.selected_notes, checkedCount, checkedCount));
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        mNotesAdapter = new NotesCursorAdapter(requireActivity().getBaseContext(), null, 0);
        setListAdapter(mNotesAdapter);
    }

    // nbradbury - load values from preferences
    protected void getPrefs() {
        mIsCondensedNoteList = PrefUtils.getBoolPref(getActivity(), PrefUtils.PREF_CONDENSED_LIST, false);
        mTitleFontSize = PrefUtils.getFontSize(getActivity());
        mPreviewFontSize = mTitleFontSize - 2;
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_notes_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        NotesActivity notesActivity = (NotesActivity) requireActivity();

        if (ACTION_NEW_NOTE.equals(notesActivity.getIntent().getAction()) &&
                !notesActivity.userIsUnauthorized()){
            //if user tap on "app shortcut", create a new note
            createNewNote("new_note_shortcut");
        }

        mPreferences = PreferenceManager.getDefaultSharedPreferences(requireContext());
        mRootView = view.findViewById(R.id.list_root);

        LinearLayout emptyView = view.findViewById(android.R.id.empty);
        emptyView.setVisibility(View.GONE);
        mEmptyListTextView = view.findViewById(R.id.empty_message);
        mEmptyListTextView.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                addNote();
            }
        });
        setEmptyListMessage("<strong>" + getString(R.string.no_notes_here) + "</strong><br />" + String.format(getString(R.string.why_not_create_one), "<u>", "</u>"));
        mDividerLine = view.findViewById(R.id.divider_line);

        if (DisplayUtils.isLargeScreenLandscape(notesActivity)) {
            setActivateOnItemClick(true);
            mDividerLine.setVisibility(View.VISIBLE);
        }

        mFloatingActionButton = view.findViewById(R.id.fab_button);
        mFloatingActionButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                createNewNote("action_bar_button");
            }
        });
        mFloatingActionButton.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (v.isHapticFeedbackEnabled()) {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }

                Toast.makeText(getContext(), requireContext().getString(R.string.new_note), Toast.LENGTH_SHORT).show();
                return true;
            }
        });

        mPreferenceSortOrder = PrefUtils.getIntPref(requireContext(), PrefUtils.PREF_SORT_ORDER);
        @SuppressLint("InflateParams")
        LinearLayout sortLayoutContainer = (LinearLayout) getLayoutInflater().inflate(R.layout.search_sort, null, false);
        mSortLayoutContent = sortLayoutContainer.findViewById(R.id.sort_content);
        mSortLayoutContent.setVisibility(mIsSearching ? View.VISIBLE : View.GONE);
        mSortOrder = sortLayoutContainer.findViewById(R.id.sort_order);
        mSortOrder.setText(R.string.sort_search_relevance);
        mSortLayoutContent.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                PopupMenu popup = new PopupMenu(mSortOrder.getContext(), mSortOrder, Gravity.START);
                MenuInflater inflater = popup.getMenuInflater();
                inflater.inflate(R.menu.search_sort, popup.getMenu());
                popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
                    @Override
                    public boolean onMenuItemClick(MenuItem item) {
                        mSortOrder.setText(item.getTitle());

                        switch (item.getItemId()) {
                            case R.id.search_alphabetically:
                                mPreferences.edit().putString(PrefUtils.PREF_SORT_ORDER,
                                    String.valueOf(mIsSortDown ? ALPHABETICAL_DESCENDING : ALPHABETICAL_ASCENDING)
                                ).apply();
                                refreshListForSearch();
                                return true;
                            case R.id.search_created:
                                mPreferences.edit().putString(PrefUtils.PREF_SORT_ORDER,
                                    String.valueOf(mIsSortDown ? DATE_CREATED_DESCENDING : DATE_CREATED_ASCENDING)
                                ).apply();
                                refreshListForSearch();
                                return true;
                            case R.id.search_modified:
                            case R.id.search_relevance:
                                mPreferences.edit().putString(PrefUtils.PREF_SORT_ORDER,
                                    String.valueOf(mIsSortDown ? DATE_MODIFIED_DESCENDING : DATE_MODIFIED_ASCENDING)
                                ).apply();
                                refreshListForSearch();
                                return true;
                            default:
                                return false;
                        }
                    }
                });
                popup.show();
            }
        });
        ListView list = view.findViewById(android.R.id.list);
        list.addHeaderView(sortLayoutContainer);

        getListView().setOnItemLongClickListener(this);
        getListView().setMultiChoiceModeListener(this);

        mSortDirection = sortLayoutContainer.findViewById(R.id.sort_direction);
        ImageView sortDirectionSwitch = sortLayoutContainer.findViewById(R.id.sort_direction_switch);
        sortDirectionSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                float startRotate = mIsSortDown ? -180f : 0f;
                float endRotate = mIsSortDown ? 0f : -180f;
                int duration = getResources().getInteger(android.R.integer.config_shortAnimTime);
                ObjectAnimator.ofFloat(mSortDirection, View.ROTATION, startRotate, endRotate).setDuration(duration).start();
                mIsSortDown = !mIsSortDown;
                switchSortDirection();
                refreshListForSearch();
            }
        });
        sortDirectionSwitch.setOnLongClickListener(new View.OnLongClickListener() {
            @Override
            public boolean onLongClick(View v) {
                if (v.isHapticFeedbackEnabled()) {
                    v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }

                Toast.makeText(requireContext(), requireContext().getString(R.string.sort_search_reverse_order), Toast.LENGTH_SHORT).show();
                return true;
            }
        });
    }

    private void switchSortDirection() {
        mSortDirection.setContentDescription(getString(mIsSortDown ? R.string.description_down : R.string.description_up));

        switch (PrefUtils.getIntPref(requireContext(), PrefUtils.PREF_SORT_ORDER)) {
            case DATE_MODIFIED_DESCENDING:
                mPreferences.edit().putString(PrefUtils.PREF_SORT_ORDER, String.valueOf(DATE_MODIFIED_ASCENDING)).apply();
                break;
            case DATE_MODIFIED_ASCENDING:
                mPreferences.edit().putString(PrefUtils.PREF_SORT_ORDER, String.valueOf(DATE_MODIFIED_DESCENDING)).apply();
                break;
            case DATE_CREATED_DESCENDING:
                mPreferences.edit().putString(PrefUtils.PREF_SORT_ORDER, String.valueOf(DATE_CREATED_ASCENDING)).apply();
                break;
            case DATE_CREATED_ASCENDING:
                mPreferences.edit().putString(PrefUtils.PREF_SORT_ORDER, String.valueOf(DATE_CREATED_DESCENDING)).apply();
                break;
            case ALPHABETICAL_ASCENDING:
                mPreferences.edit().putString(PrefUtils.PREF_SORT_ORDER, String.valueOf(ALPHABETICAL_DESCENDING)).apply();
                break;
            case ALPHABETICAL_DESCENDING:
                mPreferences.edit().putString(PrefUtils.PREF_SORT_ORDER, String.valueOf(ALPHABETICAL_ASCENDING)).apply();
                break;
        }
    }

    private void createNewNote(String label){
        if (!isAdded()) return;

        addNote();
        AnalyticsTracker.track(
                AnalyticsTracker.Stat.LIST_NOTE_CREATED,
                AnalyticsTracker.CATEGORY_NOTE,
                label
        );
    }

    @Override
    public void onAttach(@NonNull Context activity) {
        super.onAttach(activity);

        // Activities containing this fragment must implement its callbacks.
        if (!(activity instanceof Callbacks)) {
            throw new IllegalStateException("Activity must implement fragment's callbacks.");
        }

        mCallbacks = (Callbacks) activity;
    }

    @Override
    public void onResume() {
        super.onResume();
        getPrefs();

        refreshList();
    }

    @Override
    public void onDetach() {
        super.onDetach();
        // Restore sort order from Settings.
        mPreferences.edit().putString(PrefUtils.PREF_SORT_ORDER, String.valueOf(mPreferenceSortOrder)).apply();
        // Reset the active callbacks interface to the dummy implementation.
        mCallbacks = sCallbacks;
    }

    public void setEmptyListMessage(String message) {
        if (mEmptyListTextView != null && message != null)
            mEmptyListTextView.setText(HtmlCompat.fromHtml(message));
    }

    @Override
    public void onListItemClick(@NonNull ListView listView, @NonNull View view, int position, long id) {
        if (!isAdded()) return;
        super.onListItemClick(listView, view, position, id);

        NoteViewHolder holder = (NoteViewHolder) view.getTag();
        String noteID = holder.getNoteId();

        if (noteID != null) {
            Note note = mNotesAdapter.getItem(position);
            mCallbacks.onNoteSelected(noteID, position, holder.mMatchOffsets, note.isMarkdownEnabled(), note.isPreviewEnabled());
        }

        mActivatedPosition = position;
    }

    /**
     * Selects first row in the list if available
     */
    public void selectFirstNote() {
        if (mNotesAdapter.getCount() > 0) {
            Note selectedNote = mNotesAdapter.getItem(0);
            mCallbacks.onNoteSelected(selectedNote.getSimperiumKey(), 0, null, selectedNote.isMarkdownEnabled(), selectedNote.isPreviewEnabled());
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mActivatedPosition != ListView.INVALID_POSITION) {
            // Serialize and persist the activated item position.
            outState.putInt(STATE_ACTIVATED_POSITION, mActivatedPosition);
        }
    }

    public View getRootView() {
        return mRootView;
    }

    /**
     * Turns on activate-on-click mode. When this mode is on, list items will be
     * given the 'activated' state when touched.
     */
    public void setActivateOnItemClick(boolean activateOnItemClick) {
        // When setting CHOICE_MODE_SINGLE, ListView will automatically
        // give items the 'activated' state when touched.
        getListView().setChoiceMode(activateOnItemClick ? ListView.CHOICE_MODE_SINGLE : ListView.CHOICE_MODE_NONE);
    }

    public void setActivatedPosition(int position) {
        if (getListView() != null) {
            if (position == ListView.INVALID_POSITION) {
                getListView().setItemChecked(mActivatedPosition, false);
            } else {
                getListView().setItemChecked(position, true);
            }

            mActivatedPosition = position;
        }
    }

    public void setDividerVisible(boolean visible) {
        mDividerLine.setVisibility(visible ? View.VISIBLE : View.GONE);
    }

    public void setFloatingActionButtonVisible(boolean visible) {
        if (mFloatingActionButton == null) return;

        if (visible) {
            mFloatingActionButton.show();
        } else {
            mFloatingActionButton.hide();
        }
    }

    public void refreshList() {
        refreshList(false);
    }

    public void refreshList(boolean fromNav) {
        if (mRefreshListTask != null && mRefreshListTask.getStatus() != AsyncTask.Status.FINISHED)
            mRefreshListTask.cancel(true);

        mRefreshListTask = new refreshListTask();
        mRefreshListTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR, fromNav);

        WidgetUtils.updateNoteWidgets(getActivity());
    }

    private void refreshListForSearch() {
        if (mRefreshListForSearchTask != null && mRefreshListForSearchTask.getStatus() != AsyncTask.Status.FINISHED) {
            mRefreshListForSearchTask.cancel(true);
        }

        mRefreshListForSearchTask = new refreshListForSearchTask();
        mRefreshListForSearchTask.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
    }

    public void refreshListFromNavSelect() {
        refreshList(true);
    }

    public ObjectCursor<Note> queryNotes() {
        if (!isAdded()) return null;

        NotesActivity notesActivity = (NotesActivity) requireActivity();
        Query<Note> query = notesActivity.getSelectedTag().query();

        String searchString = mSearchString;
        if (hasSearchQuery()) {
            searchString = queryTags(query, mSearchString);
        }
        if (!TextUtils.isEmpty(searchString)) {
            query.where(new Query.FullTextMatch(new SearchTokenizer(searchString)));
            query.include(new Query.FullTextOffsets("match_offsets"));
            query.include(new Query.FullTextSnippet(Note.MATCHED_TITLE_INDEX_NAME, Note.TITLE_INDEX_NAME));
            query.include(new Query.FullTextSnippet(Note.MATCHED_CONTENT_INDEX_NAME, Note.CONTENT_PROPERTY));
            query.include(Note.TITLE_INDEX_NAME, Note.CONTENT_PREVIEW_INDEX_NAME);
        } else {
            query.include(Note.TITLE_INDEX_NAME, Note.CONTENT_PREVIEW_INDEX_NAME);
        }

        query.include(Note.PINNED_INDEX_NAME);
        PrefUtils.sortNoteQuery(query, requireContext(), true);
        return query.execute();
    }

    private ObjectCursor<Note> queryNotesForSearch() {
        if (!isAdded()) {
            return null;
        }

        NotesActivity notesActivity = (NotesActivity) requireActivity();
        Query<Note> query = notesActivity.getSelectedTag().query();

        String searchString = mSearchString;

        if (hasSearchQuery()) {
            searchString = queryTags(query, mSearchString);
        }

        if (!TextUtils.isEmpty(searchString)) {
            query.where(new Query.FullTextMatch(new SearchTokenizer(searchString)));
            query.include(new Query.FullTextOffsets("match_offsets"));
            query.include(new Query.FullTextSnippet(Note.MATCHED_TITLE_INDEX_NAME, Note.TITLE_INDEX_NAME));
            query.include(new Query.FullTextSnippet(Note.MATCHED_CONTENT_INDEX_NAME, Note.CONTENT_PROPERTY));
            query.include(Note.TITLE_INDEX_NAME, Note.CONTENT_PREVIEW_INDEX_NAME);
        } else {
            query.include(Note.TITLE_INDEX_NAME, Note.CONTENT_PREVIEW_INDEX_NAME);
        }

        PrefUtils.sortNoteQuery(query, requireContext(), false);
        return query.execute();
    }

    private String queryTags(Query<Note> query, String searchString) {
        Pattern pattern = Pattern.compile("tag:(.*?)( |$)");
        Matcher matcher = pattern.matcher(searchString);
        while (matcher.find()) {
            query.where(TAGS_PROPERTY, Query.ComparisonType.LIKE, matcher.group(1));
        }
        return matcher.replaceAll("");
    }

    public void addNote() {

        // Prevents jarring 'New note...' from showing in the list view when creating a new note
        NotesActivity notesActivity = (NotesActivity) requireActivity();
        if (!DisplayUtils.isLargeScreenLandscape(notesActivity))
            notesActivity.stopListeningToNotesBucket();

        // Create & save new note
        Simplenote simplenote = (Simplenote) requireActivity().getApplication();
        Bucket<Note> notesBucket = simplenote.getNotesBucket();
        final Note note = notesBucket.newObject();
        note.setCreationDate(Calendar.getInstance());
        note.setModificationDate(note.getCreationDate());
        note.setMarkdownEnabled(PrefUtils.getBoolPref(getActivity(), PrefUtils.PREF_MARKDOWN_ENABLED, false));

        if (notesActivity.getSelectedTag() != null && notesActivity.getSelectedTag().name != null) {
            String tagName = notesActivity.getSelectedTag().name;
            if (!tagName.equals(getString(R.string.all_notes)) && !tagName.equals(getString(R.string.trash)) && !tagName.equals(getString(R.string.untagged_notes)))
                note.setTagString(tagName);
        }

        note.save();

        if (DisplayUtils.isLargeScreenLandscape(getActivity())) {
            // Hack: Simperium saves async so we add a small delay to ensure the new note is truly
            // saved before proceeding.
            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    mCallbacks.onNoteSelected(note.getSimperiumKey(), 0, null, note.isMarkdownEnabled(), note.isPreviewEnabled());
                }
            }, 50);
        } else {
            Bundle arguments = new Bundle();
            arguments.putString(NoteEditorFragment.ARG_ITEM_ID, note.getSimperiumKey());
            arguments.putBoolean(NoteEditorFragment.ARG_NEW_NOTE, true);
            arguments.putBoolean(NoteEditorFragment.ARG_MARKDOWN_ENABLED, note.isMarkdownEnabled());
            arguments.putBoolean(NoteEditorFragment.ARG_PREVIEW_ENABLED, note.isPreviewEnabled());
            Intent editNoteIntent = new Intent(getActivity(), NoteEditorActivity.class);
            editNoteIntent.putExtras(arguments);

            requireActivity().startActivityForResult(editNoteIntent, Simplenote.INTENT_EDIT_NOTE);
        }
    }

    public void setNoteSelected(String selectedNoteID) {
        // Loop through notes and set note selected if found
        //noinspection unchecked
        ObjectCursor<Note> cursor = (ObjectCursor<Note>) mNotesAdapter.getCursor();
        if (cursor != null) {
            for (int i = 0; i < cursor.getCount(); i++) {
                cursor.moveToPosition(i);
                String noteKey = cursor.getSimperiumKey();
                if (noteKey != null && noteKey.equals(selectedNoteID)) {
                    setActivatedPosition(i);
                    return;
                }
            }
        }

        // Didn't find the note, let's try again after the cursor updates (see refreshListTask)
        mSelectedNoteId = selectedNoteID;
    }

    public void searchNotes(String searchString) {
        mIsSearching = true;
        mSortLayoutContent.setVisibility(View.VISIBLE);
        // Start search with Relevance sort order selected.
        mPreferences.edit().putString(PrefUtils.PREF_SORT_ORDER,
            String.valueOf(mIsSortDown ? DATE_MODIFIED_DESCENDING : DATE_MODIFIED_ASCENDING)
        ).apply();
        mSortOrder.setText(R.string.sort_search_relevance);
        refreshListForSearch();

        if (!searchString.equals(mSearchString)) {
            mSearchString = searchString;
            refreshList();
        }
    }

    /**
     * Clear search and load all notes
     */
    public void clearSearch() {
        mIsSearching = false;
        mSortLayoutContent.setVisibility(View.GONE);
        // Restore sort order from Settings.
        mPreferences.edit().putString(PrefUtils.PREF_SORT_ORDER, String.valueOf(mPreferenceSortOrder)).apply();
        refreshList();

        if (mSearchString != null && !mSearchString.equals("")) {
            mSearchString = null;
            refreshList();
        }
    }

    public boolean hasSearchQuery() {
        return mSearchString != null && !mSearchString.equals("");
    }

    /**
     * A callback interface that all activities containing this fragment must
     * implement. This mechanism allows activities to be notified of item
     * selections.
     */
    public interface Callbacks {
        /**
         * Callback for when action mode is created.
         */
        void onActionModeCreated();
        /**
         * Callback for when action mode is destroyed.
         */
        void onActionModeDestroyed();
        /**
         * Callback for when a note has been selected.
         */
        void onNoteSelected(String noteID, int position, String matchOffsets, boolean isMarkdownEnabled, boolean isPreviewEnabled);
    }

    // view holder for NotesCursorAdapter
    private static class NoteViewHolder {
        private ImageView mPinned;
        private ImageView mPublished;
        private TextView mContent;
        private TextView mDate;
        private TextView mTitle;
        private String mMatchOffsets;
        private String mNoteId;
        private View mStatus;

        public String getNoteId() {
            return mNoteId;
        }

        public void setNoteId(String noteId) {
            mNoteId = noteId;
        }
    }

    public class NotesCursorAdapter extends CursorAdapter {
        private ObjectCursor<Note> mCursor;

        private SearchSnippetFormatter.SpanFactory mSnippetHighlighter = new TextHighlighter(requireActivity(),
                R.attr.listSearchHighlightForegroundColor, R.attr.listSearchHighlightBackgroundColor);

        public NotesCursorAdapter(Context context, ObjectCursor<Note> c, int flags) {
            super(context, c, flags);
            mCursor = c;
        }

        public void changeCursor(ObjectCursor<Note> cursor) {
            mCursor = cursor;
            super.changeCursor(cursor);
        }

        @Override
        public Note getItem(int position) {
            mCursor.moveToPosition(position - 1);  // Minus one due to sort view header.
            return mCursor.getObject();
        }

        /*
         *  nbradbury - implemented "holder pattern" to boost performance with large note lists
         */
        @Override
        public View getView(final int position, View view, ViewGroup parent) {
            final NoteViewHolder holder;

            if (view == null) {
                view = View.inflate(requireActivity().getBaseContext(), R.layout.note_list_row, null);
                holder = new NoteViewHolder();
                holder.mTitle = view.findViewById(R.id.note_title);
                holder.mContent = view.findViewById(R.id.note_content);
                holder.mDate = view.findViewById(R.id.note_date);
                holder.mPinned = view.findViewById(R.id.note_pinned);
                holder.mPublished = view.findViewById(R.id.note_published);
                holder.mStatus = view.findViewById(R.id.note_status);
                view.setTag(holder);
            } else {
                holder = (NoteViewHolder) view.getTag();
            }

            if (holder.mTitle.getTextSize() != mTitleFontSize) {
                holder.mTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, mTitleFontSize);
                holder.mContent.setTextSize(TypedValue.COMPLEX_UNIT_SP, mPreviewFontSize);
                holder.mDate.setTextSize(TypedValue.COMPLEX_UNIT_SP, mPreviewFontSize);
            }

            if (position == getListView().getCheckedItemPosition())
                view.setActivated(true);
            else
                view.setActivated(false);

            // for performance reasons we are going to get indexed values
            // from the cursor instead of instantiating the entire bucket object
            holder.mContent.setVisibility(mIsCondensedNoteList ? View.GONE : View.VISIBLE);
            mCursor.moveToPosition(position);
            holder.setNoteId(mCursor.getSimperiumKey());
            Calendar date = getDateByPreference(mCursor.getObject());
            holder.mDate.setText(date != null ? DateTimeUtils.getDateTextShort(date) : "");
            holder.mDate.setVisibility(mIsSearching && date != null ? View.VISIBLE : View.GONE);
            boolean isPinned = mCursor.getObject().isPinned();
            holder.mPinned.setVisibility(!isPinned || mIsSearching ? View.GONE : View.VISIBLE);
            boolean isPublished = !mCursor.getObject().getPublishedUrl().isEmpty();
            holder.mPublished.setVisibility(!isPublished || mIsSearching ? View.GONE : View.VISIBLE);
            boolean showIcons = isPinned || isPublished;
            boolean showDate = mIsSearching && date != null;
            holder.mStatus.setVisibility(showIcons || showDate ? View.VISIBLE : View.GONE);
            String title = mCursor.getString(mCursor.getColumnIndex(Note.TITLE_INDEX_NAME));

            if (TextUtils.isEmpty(title)) {
                SpannableString newNoteString = new SpannableString(getString(R.string.new_note_list));
                newNoteString.setSpan(new TextAppearanceSpan(getActivity(),R.style.UntitledNoteAppearance),
                        0,
                        newNoteString.length(),
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                );
                newNoteString.setSpan(new AbsoluteSizeSpan(mTitleFontSize, true),
                        0,
                        newNoteString.length(),
                        SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE
                );
                holder.mTitle.setText(newNoteString);
            } else {
                SpannableStringBuilder titleChecklistString = new SpannableStringBuilder(title);
                titleChecklistString = (SpannableStringBuilder) ChecklistUtils.addChecklistSpansForRegexAndColor(
                        getContext(),
                        titleChecklistString,
                        ChecklistUtils.CHECKLIST_REGEX,
                        ThemeUtils.getThemeTextColorId(getContext()));
                holder.mTitle.setText(titleChecklistString);
            }

            holder.mMatchOffsets = null;
            int matchOffsetsIndex = mCursor.getColumnIndex("match_offsets");

            if (hasSearchQuery() && matchOffsetsIndex != -1) {
                title = mCursor.getString(mCursor.getColumnIndex(Note.MATCHED_TITLE_INDEX_NAME));
                String snippet = mCursor.getString(mCursor.getColumnIndex(Note.MATCHED_CONTENT_INDEX_NAME));
                holder.mMatchOffsets = mCursor.getString(matchOffsetsIndex);

                try {
                    holder.mContent.setText(SearchSnippetFormatter.formatString(
                            getContext(),
                            snippet,
                            mSnippetHighlighter,
                            R.color.text_title_disabled));
                    holder.mTitle.setText(SearchSnippetFormatter.formatString(
                            getContext(),
                            title,
                            mSnippetHighlighter, ThemeUtils.getThemeTextColorId(getContext())));
                } catch (NullPointerException e) {
                    title = StrUtils.notNullStr(mCursor.getString(mCursor.getColumnIndex(Note.TITLE_INDEX_NAME)));
                    holder.mTitle.setText(title);
                    String matchedContentPreview = StrUtils.notNullStr(mCursor.getString(mCursor.getColumnIndex(Note.CONTENT_PREVIEW_INDEX_NAME)));
                    holder.mContent.setText(matchedContentPreview);
                }
            } else if (!mIsCondensedNoteList) {
                String contentPreview = mCursor.getString(mCursor.getColumnIndex(Note.CONTENT_PREVIEW_INDEX_NAME));

                if (title == null || title.equals(contentPreview) || title.equals(getString(R.string.new_note_list)))
                    holder.mContent.setVisibility(View.GONE);
                else {
                    holder.mContent.setText(contentPreview);
                    SpannableStringBuilder checklistString = new SpannableStringBuilder(contentPreview);
                    checklistString = (SpannableStringBuilder) ChecklistUtils.addChecklistSpansForRegexAndColor(
                            getContext(),
                            checklistString,
                            ChecklistUtils.CHECKLIST_REGEX,
                            R.color.text_title_disabled);
                    holder.mContent.setText(checklistString);
                }
            }

            // Add mouse right click support for showing a popup menu
            view.setOnTouchListener(new View.OnTouchListener() {
                @SuppressLint("ClickableViewAccessibility")
                @Override
                public boolean onTouch(View view, MotionEvent event) {
                    if (event.getButtonState() == MotionEvent.BUTTON_SECONDARY) {
                        showPopupMenuAtPosition(view, position);
                        return true;
                    }

                    return false;
                }
            });

            return view;
        }

        @Override
        public View newView(Context context, Cursor cursor, ViewGroup viewGroup) {
            return null;
        }

        @Override
        public void bindView(View view, Context context, Cursor cursor) {
        }
    }

    private Calendar getDateByPreference(Note note) {
        switch (PrefUtils.getIntPref(requireContext(), PrefUtils.PREF_SORT_ORDER)) {
            case DATE_CREATED_ASCENDING:
            case DATE_CREATED_DESCENDING:
                return note.getCreationDate();
            case DATE_MODIFIED_ASCENDING:
            case DATE_MODIFIED_DESCENDING:
                return note.getModificationDate();
            case ALPHABETICAL_ASCENDING:
            case ALPHABETICAL_DESCENDING:
            default:
                return null;
        }
    }

    private void showPopupMenuAtPosition(View view, int position) {
        if (view.getContext() == null) {
            return;
        }

        final Note note = mNotesAdapter.getItem(position);
        if (note == null) {
            return;
        }

        PopupMenu popup = new PopupMenu(view.getContext(), view, Gravity.END);
        MenuInflater inflater = popup.getMenuInflater();
        inflater.inflate(R.menu.bulk_edit, popup.getMenu());

        if (!getListView().isLongClickable()) {
            // If viewing the trash, remove pin menu item and change trash menu title to 'Restore'
            popup.getMenu().removeItem(R.id.menu_pin);
            if (popup.getMenu().getItem(POPUP_MENU_FIRST_ITEM_POSITION) != null) {
                popup.getMenu().getItem(POPUP_MENU_FIRST_ITEM_POSITION).setTitle(R.string.restore);
            }
        } else if (popup.getMenu().getItem(POPUP_MENU_FIRST_ITEM_POSITION) != null) {
            // If not viewing the trash, set pin menu title based on note pin state
            int pinTitle = note.isPinned() ? R.string.unpin_from_top : R.string.pin_to_top;
            popup.getMenu().getItem(POPUP_MENU_FIRST_ITEM_POSITION).setTitle(pinTitle);
        }

        popup.setOnMenuItemClickListener(new PopupMenu.OnMenuItemClickListener() {
            @Override
            public boolean onMenuItemClick(MenuItem item) {
                switch (item.getItemId()) {
                    case R.id.menu_pin:
                        note.setPinned(!note.isPinned());
                        note.setModificationDate(Calendar.getInstance());
                        note.save();
                        refreshList();
                        return true;
                    case R.id.menu_delete:
                        note.setDeleted(!note.isDeleted());
                        note.setModificationDate(Calendar.getInstance());
                        note.save();
                        if (getActivity() != null) {
                            ((NotesActivity) getActivity()).updateViewsAfterTrashAction(note);
                        }
                        return true;
                    default:
                        return false;
                }
            }
        });

        popup.show();
    }

    private class refreshListTask extends AsyncTask<Boolean, Void, ObjectCursor<Note>> {
        boolean mIsFromNavSelect;

        @Override
        protected ObjectCursor<Note> doInBackground(Boolean... args) {
            mIsFromNavSelect = args[0];
            return queryNotes();
        }

        @Override
        protected void onPostExecute(ObjectCursor<Note> cursor) {
            if (cursor == null || getActivity() == null || getActivity().isFinishing())
                return;

            // While using a Query.FullTextMatch it's easy to enter an invalid term so catch the error and clear the cursor
            int count;
            try {
                mNotesAdapter.changeCursor(cursor);
                count = mNotesAdapter.getCount();
            } catch (SQLiteException e) {
                count = 0;
                Log.e(Simplenote.TAG, "Invalid SQL statement", e);
                mNotesAdapter.changeCursor(null);
            }

            NotesActivity notesActivity = (NotesActivity) getActivity();
            if (notesActivity != null) {
                if (mIsFromNavSelect && DisplayUtils.isLargeScreenLandscape(notesActivity)) {
                    if (count == 0) {
                        notesActivity.showDetailPlaceholder();
                    } else {
                        // Select the first note
                        selectFirstNote();
                    }
                }
                notesActivity.updateTrashMenuItem();
            }

            if (mSelectedNoteId != null) {
                setNoteSelected(mSelectedNoteId);
                mSelectedNoteId = null;
            }
        }
    }

    private class refreshListForSearchTask extends AsyncTask<Void, Void, ObjectCursor<Note>> {
        @Override
        protected ObjectCursor<Note> doInBackground(Void... args) {
            return queryNotesForSearch();
        }

        @Override
        protected void onPostExecute(ObjectCursor<Note> cursor) {
            if (cursor == null || getActivity() == null || getActivity().isFinishing()) {
                return;
            }

            // While using Query.FullTextMatch, it's easy to enter an invalid term so catch the error and clear the cursor.
            try {
                mNotesAdapter.changeCursor(cursor);
            } catch (SQLiteException e) {
                Log.e(Simplenote.TAG, "Invalid SQL statement", e);
                mNotesAdapter.changeCursor(null);
            }

            NotesActivity notesActivity = (NotesActivity) requireActivity();
            notesActivity.updateTrashMenuItem();

            if (mSelectedNoteId != null) {
                setNoteSelected(mSelectedNoteId);
                mSelectedNoteId = null;
            }
        }
    }

    private class pinNotesTask extends AsyncTask<Void, Void, Void> {

        private SparseBooleanArray mSelectedRows = new SparseBooleanArray();

        @Override
        protected void onPreExecute() {
            if (getListView() != null) {
                mSelectedRows = getListView().getCheckedItemPositions();
            }
        }

        @Override
        protected Void doInBackground(Void... args) {

            // Get the checked notes and add them to the pinnedNotesList
            // We can't modify the note in this loop because the adapter could change
            List<Note> pinnedNotesList = new ArrayList<>();
            for (int i = 0; i < mSelectedRows.size(); i++) {
                if (mSelectedRows.valueAt(i)) {
                    pinnedNotesList.add(mNotesAdapter.getItem(mSelectedRows.keyAt(i)));
                }
            }

            // Now loop through the notes list and mark them as pinned
            for (Note pinnedNote : pinnedNotesList) {
                pinnedNote.setPinned(!pinnedNote.isPinned());
                pinnedNote.setModificationDate(Calendar.getInstance());
                pinnedNote.save();
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {

            mActionMode.finish();
            refreshList();
        }
    }

    private class trashNotesTask extends AsyncTask<Void, Void, Void> {

        private List<String> mDeletedNoteIds = new ArrayList<>();
        private SparseBooleanArray mSelectedRows = new SparseBooleanArray();

        @Override
        protected void onPreExecute() {
            if (getListView() != null) {
                mSelectedRows = getListView().getCheckedItemPositions();
            }
        }

        @Override
        protected Void doInBackground(Void... args) {

            // Get the checked notes and add them to the deletedNotesList
            // We can't modify the note in this loop because the adapter could change
            List<Note> deletedNotesList = new ArrayList<>();
            for (int i = 0; i < mSelectedRows.size(); i++) {
                if (mSelectedRows.valueAt(i)) {
                    deletedNotesList.add(mNotesAdapter.getItem(mSelectedRows.keyAt(i)));
                }
            }

            // Now loop through the notes list and mark them as deleted
            for (Note deletedNote : deletedNotesList) {
                mDeletedNoteIds.add(deletedNote.getSimperiumKey());
                deletedNote.setDeleted(!deletedNote.isDeleted());
                deletedNote.setModificationDate(Calendar.getInstance());
                deletedNote.save();
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            NotesActivity notesActivity = ((NotesActivity) getActivity());
            if (notesActivity != null)
                notesActivity.showUndoBarWithNoteIds(mDeletedNoteIds);

            refreshList();
        }
    }
}
