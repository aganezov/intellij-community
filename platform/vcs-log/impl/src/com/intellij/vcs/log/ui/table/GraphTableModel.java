package com.intellij.vcs.log.ui.table;

import com.intellij.openapi.util.EmptyRunnable;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.Consumer;
import com.intellij.util.NotNullFunction;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.util.text.DateFormatUtil;
import com.intellij.vcs.log.*;
import com.intellij.vcs.log.data.CommitIdByStringCondition;
import com.intellij.vcs.log.data.DataGetter;
import com.intellij.vcs.log.data.RefsModel;
import com.intellij.vcs.log.data.VcsLogData;
import com.intellij.vcs.log.ui.render.GraphCommitCell;
import com.intellij.vcs.log.util.VcsUserUtil;
import com.intellij.vcs.log.visible.VisiblePack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.table.AbstractTableModel;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;

public class GraphTableModel extends AbstractTableModel {
  public static final int ROOT_COLUMN = 0;
  public static final int COMMIT_COLUMN = 1;
  public static final int AUTHOR_COLUMN = 2;
  public static final int DATE_COLUMN = 3;
  private static final int COLUMN_COUNT = DATE_COLUMN + 1;
  private static final String[] COLUMN_NAMES = {"", "Subject", "Author", "Date"};

  private static final int UP_PRELOAD_COUNT = 20;
  private static final int DOWN_PRELOAD_COUNT = 40;

  public static final int COMMIT_NOT_FOUND = -1;
  public static final int COMMIT_DOES_NOT_MATCH = -2;

  @NotNull private final VcsLogData myLogData;
  @NotNull private final Consumer<? super Runnable> myRequestMore;

  @NotNull protected VisiblePack myDataPack;

  private boolean myMoreRequested;

  public GraphTableModel(@NotNull VisiblePack dataPack, @NotNull VcsLogData logData, @NotNull Consumer<? super Runnable> requestMore) {
    myLogData = logData;
    myDataPack = dataPack;
    myRequestMore = requestMore;
  }

  @Override
  public int getRowCount() {
    return myDataPack.getVisibleGraph().getVisibleCommitCount();
  }

  @NotNull
  public VirtualFile getRoot(int rowIndex) {
    return myDataPack.getRoot(rowIndex);
  }

  @NotNull
  public Integer getIdAtRow(int row) {
    return myDataPack.getVisibleGraph().getRowInfo(row).getCommit();
  }

  @Nullable
  public CommitId getCommitIdAtRow(int row) {
    return myLogData.getCommitId(getIdAtRow(row));
  }

  public int getRowOfCommit(@NotNull Hash hash, @NotNull VirtualFile root) {
    if (!myLogData.getStorage().containsCommit(new CommitId(hash, root))) return COMMIT_NOT_FOUND;
    return getRowOfCommitWithoutCheck(hash, root);
  }

  public int getRowOfCommitByPartOfHash(@NotNull String partialHash) {
    CommitIdByStringCondition hashByString = new CommitIdByStringCondition(partialHash);
    Ref<Boolean> commitExists = new Ref<>(false);
    CommitId commitId = myLogData.getStorage().findCommitId(
      commitId1 -> {
        if (hashByString.value(commitId1)) {
          commitExists.set(true);
          return getRowOfCommitWithoutCheck(commitId1.getHash(), commitId1.getRoot()) >= 0;
        }
        return false;
      });
    return commitId != null
           ? getRowOfCommitWithoutCheck(commitId.getHash(), commitId.getRoot())
           : (commitExists.get() ? COMMIT_DOES_NOT_MATCH : COMMIT_NOT_FOUND);
  }

  private int getRowOfCommitWithoutCheck(@NotNull Hash hash, @NotNull VirtualFile root) {
    int commitIndex = myLogData.getCommitIndex(hash, root);
    Integer rowIndex = myDataPack.getVisibleGraph().getVisibleRowIndex(commitIndex);
    return rowIndex == null ? COMMIT_DOES_NOT_MATCH : rowIndex;
  }

  @Override
  public final int getColumnCount() {
    return COLUMN_COUNT;
  }

  /**
   * Requests the proper data provider to load more data from the log & recreate the model.
   *
   * @param onLoaded will be called upon task completion on the EDT.
   */
  public void requestToLoadMore(@NotNull Runnable onLoaded) {
    myMoreRequested = true;
    myRequestMore.consume(onLoaded);
  }

  @NotNull
  @Override
  public final Object getValueAt(int rowIndex, int columnIndex) {
    if (rowIndex >= getRowCount() - 1 && canRequestMore()) {
      requestToLoadMore(EmptyRunnable.INSTANCE);
    }

    VcsShortCommitDetails data = getCommitMetadata(rowIndex);
    switch (columnIndex) {
      case ROOT_COLUMN:
        return getRoot(rowIndex);
      case COMMIT_COLUMN:
        return new GraphCommitCell(data.getSubject(), getRefsAtRow(rowIndex),
                                   myDataPack.getVisibleGraph().getRowInfo(rowIndex).getPrintElements());
      case AUTHOR_COLUMN:
        String authorString = VcsUserUtil.getShortPresentation(data.getAuthor());
        return authorString + (VcsUserUtil.isSamePerson(data.getAuthor(), data.getCommitter()) ? "" : "*");
      case DATE_COLUMN:
        if (data.getAuthorTime() < 0) {
          return "";
        }
        else {
          return DateFormatUtil.formatDateTime(data.getAuthorTime());
        }
      default:
        throw new IllegalArgumentException("columnIndex is " + columnIndex + " > " + (getColumnCount() - 1));
    }
  }

  /**
   * Returns true if not all data has been loaded, i.e. there is sense to {@link #requestToLoadMore(Runnable) request more data}.
   */
  public boolean canRequestMore() {
    return !myMoreRequested && myDataPack.canRequestMore();
  }

  @Override
  public Class<?> getColumnClass(int column) {
    switch (column) {
      case ROOT_COLUMN:
        return VirtualFile.class;
      case COMMIT_COLUMN:
        return GraphCommitCell.class;
      case AUTHOR_COLUMN:
        return String.class;
      case DATE_COLUMN:
        return String.class;
      default:
        throw new IllegalArgumentException("columnIndex is " + column + " > " + (getColumnCount() - 1));
    }
  }

  @Override
  public String getColumnName(int column) {
    return COLUMN_NAMES[column];
  }

  public void setVisiblePack(@NotNull VisiblePack visiblePack) {
    myDataPack = visiblePack;
    myMoreRequested = false;
    fireTableDataChanged();
  }

  @NotNull
  public VisiblePack getVisiblePack() {
    return myDataPack;
  }

  @NotNull
  public VcsFullCommitDetails getFullDetails(int row) {
    return getDetails(row, myLogData.getCommitDetailsGetter());
  }

  @NotNull
  public VcsCommitMetadata getCommitMetadata(int row) {
    return getDetails(row, myLogData.getMiniDetailsGetter());
  }

  @NotNull
  private <T extends VcsShortCommitDetails> T getDetails(int row, @NotNull DataGetter<T> dataGetter) {
    Iterable<Integer> iterable = createRowsIterable(row, UP_PRELOAD_COUNT, DOWN_PRELOAD_COUNT, getRowCount());
    return dataGetter.getCommitData(getIdAtRow(row), iterable);
  }

  @NotNull
  public Collection<VcsRef> getRefsAtRow(int row) {
    return ((RefsModel)myDataPack.getRefs()).refsToCommit(getIdAtRow(row));
  }

  @NotNull
  public List<VcsRef> getBranchesAtRow(int row) {
    return ContainerUtil.filter(getRefsAtRow(row), ref -> ref.getType().isBranch());
  }

  @NotNull
  private Iterable<Integer> createRowsIterable(final int row, final int above, final int below, final int maxRows) {
    return () -> new Iterator<Integer>() {
      private int myRowIndex = Math.max(0, row - above);

      @Override
      public boolean hasNext() {
        return myRowIndex < row + below && myRowIndex < maxRows;
      }

      @Override
      public Integer next() {
        int nextRow = myRowIndex;
        myRowIndex++;
        return getIdAtRow(nextRow);
      }

      @Override
      public void remove() {
        throw new UnsupportedOperationException("Removing elements is not supported.");
      }
    };
  }

  @NotNull
  public List<Integer> convertToCommitIds(@NotNull List<Integer> rows) {
    return ContainerUtil.map(rows, (NotNullFunction<Integer, Integer>)this::getIdAtRow);
  }
}
