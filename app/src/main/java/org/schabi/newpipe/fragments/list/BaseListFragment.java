package org.schabi.newpipe.fragments.list;

import static org.schabi.newpipe.ktx.ViewUtils.animate;
import static org.schabi.newpipe.ktx.ViewUtils.animateHideRecyclerViewAllowingScrolling;

import android.content.Context;
import android.graphics.Color;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Bundle;
import android.util.Log;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AlertDialog;
import androidx.preference.PreferenceManager;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.snackbar.Snackbar;

import org.schabi.newpipe.R;
import org.schabi.newpipe.download.BatchDownloadStarter;
import org.schabi.newpipe.download.BatchDownloadStore;
import org.schabi.newpipe.error.ErrorUtil;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.fragments.BaseStateFragment;
import org.schabi.newpipe.fragments.OnScrollBelowItemsListener;
import org.schabi.newpipe.info_list.InfoListAdapter;
import org.schabi.newpipe.info_list.ItemViewMode;
import org.schabi.newpipe.info_list.dialog.InfoItemDialog;
import org.schabi.newpipe.util.NavigationHelper;
import org.schabi.newpipe.util.PermissionHelper;
import org.schabi.newpipe.util.OnClickGesture;
import org.schabi.newpipe.util.StateSaver;
import org.schabi.newpipe.util.ThemeHelper;
import org.schabi.newpipe.views.SuperScrollLayoutManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.function.Supplier;

public abstract class BaseListFragment<I, N> extends BaseStateFragment<I>
        implements ListViewContract<I, N>, StateSaver.WriteRead,
        SharedPreferences.OnSharedPreferenceChangeListener {
    private static final int LIST_MODE_UPDATE_FLAG = 0x32;
    protected org.schabi.newpipe.util.SavedState savedState;

    private boolean useDefaultStateSaving = true;
    private int updateFlags = 0;

    /*//////////////////////////////////////////////////////////////////////////
    // Views
    //////////////////////////////////////////////////////////////////////////*/

    protected InfoListAdapter infoListAdapter;
    protected RecyclerView itemsList;
    private int focusedPosition = -1;
    @Nullable
    private Snackbar selectionBar;

    /*//////////////////////////////////////////////////////////////////////////
    // LifeCycle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onAttach(@NonNull final Context context) {
        super.onAttach(context);

        if (infoListAdapter == null) {
            infoListAdapter = new InfoListAdapter(activity);
        }
    }

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        PreferenceManager.getDefaultSharedPreferences(activity)
                .registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (useDefaultStateSaving) {
            StateSaver.onDestroy(savedState);
        }
        PreferenceManager.getDefaultSharedPreferences(activity)
                .unregisterOnSharedPreferenceChangeListener(this);
        if (infoListAdapter != null) {
            infoListAdapter.setOnSelectionChangedListener(null);
        }
        if (selectionBar != null) {
            selectionBar.dismiss();
            selectionBar = null;
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (updateFlags != 0) {
            if ((updateFlags & LIST_MODE_UPDATE_FLAG) != 0) {
                refreshItemViewMode();
            }
            updateFlags = 0;
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // State Saving
    //////////////////////////////////////////////////////////////////////////*/

    /**
     * If the default implementation of {@link StateSaver.WriteRead} should be used.
     *
     * @param useDefaultStateSaving Whether the default implementation should be used
     * @see StateSaver
     */
    public void setUseDefaultStateSaving(final boolean useDefaultStateSaving) {
        this.useDefaultStateSaving = useDefaultStateSaving;
    }

    @Override
    public String generateSuffix() {
        // Naive solution, but it's good for now (the items don't change)
        return "." + infoListAdapter.getItemsList().size() + ".list";
    }

    private int getFocusedPosition() {
        try {
            final View focusedItem = itemsList.getFocusedChild();
            final RecyclerView.ViewHolder itemHolder =
                    itemsList.findContainingViewHolder(focusedItem);
            return itemHolder.getBindingAdapterPosition();
        } catch (final NullPointerException e) {
            return -1;
        }
    }

    @Override
    public void writeTo(final Queue<Object> objectsToSave) {
        if (!useDefaultStateSaving) {
            return;
        }

        objectsToSave.add(infoListAdapter.getItemsList());
        objectsToSave.add(getFocusedPosition());
    }

    @Override
    @SuppressWarnings("unchecked")
    public void readFrom(@NonNull final Queue<Object> savedObjects) throws Exception {
        if (!useDefaultStateSaving) {
            return;
        }

        infoListAdapter.getItemsList().clear();
        infoListAdapter.getItemsList().addAll((List<InfoItem>) savedObjects.poll());
        restoreFocus((Integer) savedObjects.poll());
    }

    private void restoreFocus(final Integer position) {
        if (position == null || position < 0) {
            return;
        }

        itemsList.post(() -> {
            final RecyclerView.ViewHolder focusedHolder =
                    itemsList.findViewHolderForAdapterPosition(position);

            if (focusedHolder != null) {
                focusedHolder.itemView.requestFocus();
            }
        });
    }

    @Override
    public void onSaveInstanceState(@NonNull final Bundle bundle) {
        super.onSaveInstanceState(bundle);
        if (useDefaultStateSaving) {
            savedState = StateSaver
                    .tryToSave(activity.isChangingConfigurations(), savedState, bundle, this);
        }
    }

    @Override
    protected void onRestoreInstanceState(@NonNull final Bundle bundle) {
        super.onRestoreInstanceState(bundle);
        if (useDefaultStateSaving) {
            savedState = StateSaver.tryToRestore(bundle, this);
        }
    }

    @Override
    public void onStop() {
        focusedPosition = getFocusedPosition();
        super.onStop();
    }

    @Override
    public void onStart() {
        super.onStart();
        restoreFocus(focusedPosition);
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Init
    //////////////////////////////////////////////////////////////////////////*/

    @Nullable
    protected Supplier<View> getListHeaderSupplier() {
        return null;
    }

    protected RecyclerView.LayoutManager getListLayoutManager() {
        return new SuperScrollLayoutManager(activity);
    }

    protected RecyclerView.LayoutManager getGridLayoutManager() {
        final Resources resources = activity.getResources();
        int width = resources.getDimensionPixelSize(R.dimen.video_item_grid_thumbnail_image_width);
        width += (24 * resources.getDisplayMetrics().density);
        final int spanCount = Math.floorDiv(resources.getDisplayMetrics().widthPixels, width);
        final GridLayoutManager lm = new GridLayoutManager(activity, spanCount);
        lm.setSpanSizeLookup(infoListAdapter.getSpanSizeLookup(spanCount));
        return lm;
    }

    /**
     * Updates the item view mode based on user preference.
     */
    private void refreshItemViewMode() {
        final ItemViewMode itemViewMode = getItemViewMode();
        itemsList.setLayoutManager((itemViewMode == ItemViewMode.GRID)
                ? getGridLayoutManager() : getListLayoutManager());
        infoListAdapter.setItemViewMode(itemViewMode);
        infoListAdapter.notifyDataSetChanged();
    }

    @Override
    protected void initViews(final View rootView, final Bundle savedInstanceState) {
        super.initViews(rootView, savedInstanceState);

        itemsList = rootView.findViewById(R.id.items_list);
        refreshItemViewMode();

        final Supplier<View> listHeaderSupplier = getListHeaderSupplier();
        if (listHeaderSupplier != null) {
            infoListAdapter.setHeaderSupplier(listHeaderSupplier);
        }

        itemsList.setAdapter(infoListAdapter);
        updateGoldSelectionBar();
    }

    protected void onItemSelected(final InfoItem selectedItem) {
        if (DEBUG) {
            Log.d(TAG, "onItemSelected() called with: selectedItem = [" + selectedItem + "]");
        }
    }

    @Override
    protected void initListeners() {
        super.initListeners();
        infoListAdapter.setOnSelectionChangedListener(() -> {
            if (isAdded()) {
                requireActivity().invalidateOptionsMenu();
                updateGoldSelectionBar();
            }
        });
        infoListAdapter.setOnStreamSelectedListener(new OnClickGesture<>() {
            @Override
            public void selected(final StreamInfoItem selectedItem) {
                if (infoListAdapter.isSelectionMode()) {
                    infoListAdapter.toggleStreamSelection(selectedItem);
                } else {
                    onStreamSelected(selectedItem);
                }
            }

            @Override
            public void held(final StreamInfoItem selectedItem) {
                if (infoListAdapter.canSelectStream(selectedItem)) {
                    infoListAdapter.toggleStreamSelection(selectedItem);
                } else {
                    showInfoItemDialog(selectedItem);
                }
            }
        });

        infoListAdapter.setOnChannelSelectedListener(selectedItem -> {
            try {
                onItemSelected(selectedItem);
                NavigationHelper.openChannelFragment(getFM(), selectedItem.getServiceId(),
                        selectedItem.getUrl(), selectedItem.getName());
            } catch (final Exception e) {
                ErrorUtil.showUiErrorSnackbar(this, "Opening channel fragment", e);
            }
        });

        infoListAdapter.setOnPlaylistSelectedListener(selectedItem -> {
            try {
                onItemSelected(selectedItem);
                NavigationHelper.openPlaylistFragment(getFM(), selectedItem.getServiceId(),
                        selectedItem.getUrl(), selectedItem.getName());
            } catch (final Exception e) {
                ErrorUtil.showUiErrorSnackbar(this, "Opening playlist fragment", e);
            }
        });

        infoListAdapter.setOnCommentsSelectedListener(this::onItemSelected);

        // Ensure that there is always a scroll listener (e.g. when rotating the device)
        useNormalItemListScrollListener();
    }

    /**
     * Removes all listeners and adds the normal scroll listener to the {@link #itemsList}.
     */
    protected void useNormalItemListScrollListener() {
        if (DEBUG) {
            Log.d(TAG, "useNormalItemListScrollListener called");
        }
        itemsList.clearOnScrollListeners();
        itemsList.addOnScrollListener(new DefaultItemListOnScrolledDownListener());
    }

    /**
     * Removes all listeners and adds the initial scroll listener to the {@link #itemsList}.
     * <br/>
     * Which tries to load more items when not enough are in the view (not scrollable)
     * and more are available.
     * <br/>
     * Note: This method only works because "This callback will also be called if visible
     * item range changes after a layout calculation. In that case, dx and dy will be 0."
     * - which might be unexpected because no actual scrolling occurs...
     * <br/>
     * This listener will be replaced by DefaultItemListOnScrolledDownListener when
     * <ul>
     *     <li>the view was actually scrolled</li>
     *     <li>the view is scrollable</li>
     *     <li>no more items can be loaded</li>
     * </ul>
     */
    protected void useInitialItemListLoadScrollListener() {
        if (DEBUG) {
            Log.d(TAG, "useInitialItemListLoadScrollListener called");
        }
        itemsList.clearOnScrollListeners();
        itemsList.addOnScrollListener(new DefaultItemListOnScrolledDownListener() {
            @Override
            public void onScrolled(@NonNull final RecyclerView recyclerView,
                                   final int dx, final int dy) {
                super.onScrolled(recyclerView, dx, dy);

                if (dy != 0) {
                    log("Vertical scroll occurred");

                    useNormalItemListScrollListener();
                    return;
                }
                if (isLoading.get()) {
                    log("Still loading data -> Skipping");
                    return;
                }
                if (!hasMoreItems()) {
                    log("No more items to load");

                    useNormalItemListScrollListener();
                    return;
                }
                if (itemsList.canScrollVertically(1)
                        || itemsList.canScrollVertically(-1)) {
                    log("View is scrollable");

                    useNormalItemListScrollListener();
                    return;
                }

                log("Loading more data");
                loadMoreItems();
            }

            private void log(final String msg) {
                if (DEBUG) {
                    Log.d(TAG, "initItemListLoadScrollListener - " + msg);
                }
            }
        });
    }

    class DefaultItemListOnScrolledDownListener extends OnScrollBelowItemsListener {
        @Override
        public void onScrolledDown(final RecyclerView recyclerView) {
            onScrollToBottom();
        }
    }

    private void onStreamSelected(final StreamInfoItem selectedItem) {
        onItemSelected(selectedItem);
        NavigationHelper.openVideoDetailFragment(requireContext(), getFM(),
                selectedItem.getServiceId(), selectedItem.getUrl(), selectedItem.getName(),
                null, false);
    }

    protected void onScrollToBottom() {
        if (hasMoreItems() && !isLoading.get()) {
            loadMoreItems();
        }
    }

    protected void showInfoItemDialog(final StreamInfoItem item) {
        try {
            new InfoItemDialog.Builder(getActivity(), getContext(), this, item).create().show();
        } catch (final IllegalArgumentException e) {
            InfoItemDialog.Builder.reportErrorDuringInitialization(e, item);
        }
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Menu
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void onCreateOptionsMenu(@NonNull final Menu menu,
                                    @NonNull final MenuInflater inflater) {
        if (DEBUG) {
            Log.d(TAG, "onCreateOptionsMenu() called with: "
                    + "menu = [" + menu + "], inflater = [" + inflater + "]");
        }
        super.onCreateOptionsMenu(menu, inflater);
        inflater.inflate(R.menu.gold_multi_select, menu);
        final boolean selectionActive = infoListAdapter != null
                && infoListAdapter.isSelectionMode();
        for (final int id : new int[]{R.id.action_gold_download_selected,
                R.id.action_gold_select_all, R.id.action_gold_invert_selection,
                R.id.action_gold_clear_selection}) {
            final MenuItem action = menu.findItem(id);
            if (action != null) {
                action.setVisible(selectionActive);
            }
        }
        final MenuItem download = menu.findItem(R.id.action_gold_download_selected);
        if (download != null && selectionActive) {
            download.setTitle(getString(R.string.gold_multi_download_count,
                    infoListAdapter.getSelectedStreamCount()));
        }
        final ActionBar supportActionBar = activity.getSupportActionBar();
        if (supportActionBar != null) {
            supportActionBar.setDisplayShowTitleEnabled(true);
            supportActionBar.setDisplayHomeAsUpEnabled(!useAsFrontPage);
        }
    }

    private void updateGoldSelectionBar() {
        if (itemsList == null) {
            return;
        }
        if (selectionBar != null) {
            selectionBar.dismiss();
            selectionBar = null;
        }
        if (infoListAdapter != null && infoListAdapter.isSelectionMode()) {
            selectionBar = Snackbar.make(itemsList,
                    getString(R.string.gold_multi_selected_count,
                            infoListAdapter.getSelectedStreamCount()),
                    Snackbar.LENGTH_INDEFINITE);
            selectionBar.setAction(R.string.gold_multi_download, view -> {
                showGoldBatchDownloadDialog();
            });
            selectionBar.setBackgroundTint(Color.BLACK);
            selectionBar.setTextColor(Color.WHITE);
            selectionBar.setActionTextColor(Color.WHITE);
            selectionBar.show();
        }
    }

    /**
     * Handles selection toolbar actions before child fragments process their own menu actions.
     *
     * @param item selected toolbar action
     * @return true if this is a multi-selection action
     */
    protected boolean handleGoldSelectionAction(@NonNull final MenuItem item) {
        if (item.getItemId() == R.id.action_gold_select_all) {
            infoListAdapter.selectAllVisibleStreams();
            return true;
        }
        if (item.getItemId() == R.id.action_gold_invert_selection) {
            infoListAdapter.invertVisibleStreamSelection();
            return true;
        }
        if (item.getItemId() == R.id.action_gold_manage_batch) {
            showGoldBatchStatus();
            return true;
        }
        if (item.getItemId() == R.id.action_gold_clear_selection) {
            infoListAdapter.clearStreamSelection();
            return true;
        }
        if (item.getItemId() == R.id.action_gold_download_selected) {
            showGoldBatchDownloadDialog();
            return true;
        }
        return false;
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull final MenuItem item) {
        return handleGoldSelectionAction(item) || super.onOptionsItemSelected(item);
    }

    protected boolean clearGoldSelectionIfActive() {
        if (infoListAdapter != null && infoListAdapter.isSelectionMode()) {
            infoListAdapter.clearStreamSelection();
            return true;
        }
        return false;
    }

    private void showGoldBatchStatus() {
        final Context context = requireContext();
        final List<BatchDownloadStore.Entry> entries = BatchDownloadStore.snapshot(context);
        if (entries.isEmpty()) {
            new AlertDialog.Builder(context)
                    .setTitle(R.string.gold_batch_manage)
                    .setMessage(R.string.gold_batch_empty)
                    .setPositiveButton(R.string.ok, null)
                    .show();
            return;
        }

        final ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_list_item_1, new ArrayList<>());
        final AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(R.string.gold_batch_manage)
                .setAdapter(adapter, (d, which) -> {
                    final List<BatchDownloadStore.Entry> current =
                            BatchDownloadStore.snapshot(context);
                    if (which >= 0 && which < current.size()) {
                        showGoldEntryOptions(current.get(which));
                    }
                })
                .setPositiveButton(R.string.gold_batch_retry, (d, which) -> {
                    BatchDownloadStore.retryFailures(context);
                    BatchDownloadStarter.resumePending(context);
                })
                .setNeutralButton(R.string.gold_batch_cancel_pending, (d, which) -> {
                    BatchDownloadStore.cancelPending(context);
                })
                .setNegativeButton(R.string.cancel, null)
                .create();

        final Runnable update = () -> {
            if (!isAdded()) {
                return;
            }
            final List<BatchDownloadStore.Entry> current = BatchDownloadStore.snapshot(context);
            adapter.clear();
            int queued = 0;
            int failed = 0;
            int skipped = 0;
            int pending = 0;
            for (final BatchDownloadStore.Entry item : current) {
                final int statusText;
                switch (item.status) {
                    case BatchDownloadStore.QUEUED:
                        statusText = R.string.gold_batch_status_queued;
                        queued++;
                        break;
                    case BatchDownloadStore.FAILED:
                        statusText = R.string.gold_batch_status_failed;
                        failed++;
                        break;
                    case BatchDownloadStore.SKIPPED:
                        statusText = R.string.gold_batch_status_skipped;
                        skipped++;
                        break;
                    case BatchDownloadStore.CANCELLED:
                        statusText = R.string.gold_batch_status_cancelled;
                        break;
                    default:
                        statusText = R.string.gold_batch_status_pending;
                        pending++;
                }
                adapter.add(getString(statusText) + " · " + item.title
                        + (item.error.isBlank() ? "" : " (" + item.error + ")"));
            }
            dialog.setTitle(getString(R.string.gold_batch_status_progress,
                    queued, current.size(), failed, skipped, pending));
            adapter.notifyDataSetChanged();
        };
        BatchDownloadStore.addListener(update);
        dialog.setOnDismissListener(d -> BatchDownloadStore.removeListener(update));
        dialog.show();
        update.run();
    }

    private void showGoldEntryOptions(@NonNull final BatchDownloadStore.Entry entry) {
        if (BatchDownloadStore.QUEUED.equals(entry.status)) {
            return; // Already handed to NewPipe's normal download manager.
        }
        new AlertDialog.Builder(requireContext())
                .setTitle(entry.title)
                .setItems(new String[]{getString(R.string.gold_batch_retry_item),
                        getString(R.string.gold_batch_remove_pending)}, (dialog, which) -> {
                    if (which == 0) {
                        BatchDownloadStore.retry(requireContext(), entry);
                        BatchDownloadStarter.resumePending(requireContext());
                    } else {
                        BatchDownloadStore.removePending(requireContext(), entry);
                    }
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private Spinner goldSpinner(@NonNull final LinearLayout form, final int title,
                                @NonNull final String[] options) {
        final Context context = requireContext();
        final TextView label = new TextView(context);
        label.setText(title);
        label.setPadding(0, 12, 0, 2);
        final LinearLayout group = new LinearLayout(context);
        group.setOrientation(LinearLayout.VERTICAL);
        group.addView(label);
        final Spinner spinner = new Spinner(context);
        final ArrayAdapter<String> adapter = new ArrayAdapter<>(context,
                android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        group.addView(spinner);
        form.addView(group);
        spinner.setTag(group);
        return spinner;
    }

    private void showGoldBatchDownloadDialog() {
        final List<StreamInfoItem> selected = infoListAdapter.getSelectedStreams();
        if (selected.isEmpty()) {
            return;
        }
        final Context context = requireContext();
        final LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        final int padding = (int) (16 * getResources().getDisplayMetrics().density);
        form.setPadding(padding, 4, padding, 4);

        final Spinner type = goldSpinner(form, R.string.gold_multi_download,
                new String[]{getString(R.string.gold_batch_video),
                        getString(R.string.gold_batch_audio)});
        final Spinner quality = goldSpinner(form, R.string.gold_batch_select_quality,
                new String[]{getString(R.string.gold_batch_quality_auto),
                        "360p", "480p", "720p", "1080p"});
        final Spinner format = goldSpinner(form, R.string.gold_batch_select_audio_format,
                new String[]{getString(R.string.gold_batch_audio_format_auto),
                        getString(R.string.gold_batch_audio_format_mp3),
                        getString(R.string.gold_batch_audio_format_m4a),
                        getString(R.string.gold_batch_audio_format_opus),
                        getString(R.string.gold_batch_audio_format_webm)});
        final TextView formatHint = new TextView(context);
        formatHint.setText(R.string.gold_batch_audio_format_note);
        form.addView(formatHint);
        final Spinner track = goldSpinner(form, R.string.gold_batch_select_track,
                new String[]{getString(R.string.gold_batch_track_auto),
                        getString(R.string.gold_batch_track_german_original),
                        getString(R.string.gold_batch_track_german_dubbed),
                        getString(R.string.gold_batch_track_video_audio)});
        final AdapterView.OnItemSelectedListener formatVisibility =
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(final AdapterView<?> parent, final View view,
                                               final int position, final long id) {
                        final boolean audio = type.getSelectedItemPosition() == 1;
                        ((View) format.getTag()).setVisibility(audio ? View.VISIBLE : View.GONE);
                        formatHint.setVisibility(audio ? View.VISIBLE : View.GONE);
                        ((View) quality.getTag()).setVisibility(audio ? View.GONE : View.VISIBLE);
                    }

                    @Override
                    public void onNothingSelected(final AdapterView<?> parent) { }
                };
        type.setOnItemSelectedListener(formatVisibility);
        ((View) format.getTag()).setVisibility(View.GONE);
        formatHint.setVisibility(View.GONE);
        final CheckBox wifiOnly = new CheckBox(context);
        wifiOnly.setText(R.string.gold_batch_wifi_only);
        wifiOnly.setChecked(true);
        form.addView(wifiOnly);

        new AlertDialog.Builder(context)
                .setTitle(getString(R.string.gold_multi_download_count, selected.size()))
                .setView(form)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(R.string.download, (dialog, which) -> {
                    if (!PermissionHelper.checkStoragePermissions(requireActivity(),
                            PermissionHelper.DOWNLOAD_DIALOG_REQUEST_CODE)) {
                        return;
                    }
                    final String[] qualities = {"", "360p", "480p", "720p", "1080p"};
                    final String[] audioTracks = {
                            BatchDownloadStore.AUDIO_AUTO,
                            BatchDownloadStore.AUDIO_ORIGINAL,
                            BatchDownloadStore.AUDIO_DUBBED,
                            BatchDownloadStore.AUDIO_EMBEDDED
                    };
                    final String[] formats = {
                            BatchDownloadStore.FORMAT_AUTO,
                            BatchDownloadStore.FORMAT_MP3,
                            BatchDownloadStore.FORMAT_M4A,
                            BatchDownloadStore.FORMAT_OPUS,
                            BatchDownloadStore.FORMAT_WEBM
                    };
                    final BatchDownloadStore.Options options =
                            new BatchDownloadStore.Options(type.getSelectedItemPosition() == 1,
                                    qualities[quality.getSelectedItemPosition()],
                                    audioTracks[track.getSelectedItemPosition()],
                                    formats[format.getSelectedItemPosition()],
                                    wifiOnly.isChecked());
                    BatchDownloadStarter.start(context, selected, options);
                    infoListAdapter.clearStreamSelection();
                })
                .show();
    }

    /*//////////////////////////////////////////////////////////////////////////
    // Load and handle
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    protected void startLoading(final boolean forceLoad) {
        useInitialItemListLoadScrollListener();
        super.startLoading(forceLoad);
    }

    protected abstract void loadMoreItems();

    protected abstract boolean hasMoreItems();

    /*//////////////////////////////////////////////////////////////////////////
    // Contract
    //////////////////////////////////////////////////////////////////////////*/

    @Override
    public void showLoading() {
        super.showLoading();
        animateHideRecyclerViewAllowingScrolling(itemsList);
    }

    @Override
    public void hideLoading() {
        super.hideLoading();
        animate(itemsList, true, 300);
    }

    @Override
    public void showEmptyState() {
        super.showEmptyState();
        showListFooter(false);
        animateHideRecyclerViewAllowingScrolling(itemsList);
    }

    @Override
    public void showListFooter(final boolean show) {
        itemsList.post(() -> {
            if (infoListAdapter != null && itemsList != null) {
                infoListAdapter.showFooter(show);
            }
        });
    }

    @Override
    public void handleNextItems(final N result) {
        isLoading.set(false);
    }

    @Override
    public void handleError() {
        super.handleError();
        showListFooter(false);
        animateHideRecyclerViewAllowingScrolling(itemsList);
    }

    @Override
    public void onSharedPreferenceChanged(final SharedPreferences sharedPreferences,
                                          final String key) {
        if (getString(R.string.list_view_mode_key).equals(key)) {
            updateFlags |= LIST_MODE_UPDATE_FLAG;
        }
    }

    /**
     * Returns preferred item view mode.
     * @return ItemViewMode
     */
    protected ItemViewMode getItemViewMode() {
        return ThemeHelper.getItemViewMode(requireContext());
    }
}
