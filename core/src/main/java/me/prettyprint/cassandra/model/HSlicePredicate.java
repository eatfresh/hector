package me.prettyprint.cassandra.model;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import me.prettyprint.cassandra.utils.Assert;
import me.prettyprint.hector.api.Serializer;
import me.prettyprint.hector.api.exceptions.HectorException;

import org.apache.cassandra.thrift.SlicePredicate;
import org.apache.cassandra.thrift.SliceRange;

/**
 * Hector's version of cassandra SlicePredicate
 *
 * @author Ran Tavory
 * @author zznate
 */
public final class HSlicePredicate<N> implements Cloneable {
  
  protected Collection<N> columnNames;
  protected N start;
  protected N finish;
  protected boolean reversed;
  protected int count;
  /** Is count already set? */
  private boolean countSet = false;
  protected final Serializer<N> columnNameSerializer;
  protected enum PredicateType {Unknown, ColumnNames, Range};
  protected PredicateType predicateType = PredicateType.Unknown;

  private static final ByteBuffer EMPTY_BYTE_BUFFER = ByteBuffer.wrap(new byte[0]);
  /** The default count size of a range (100) */
  public static final int DEF_COUNT = 100;
  
  public HSlicePredicate(Serializer<N> columnNameSerializer) {
    Assert.notNull(columnNameSerializer, "columnNameSerializer can't be null");
    this.columnNameSerializer = columnNameSerializer;
  }

  /**
   * Sets the column names to be retrieved by this query
   *
   * @param columnNames
   *          a list of column names
   */
  public HSlicePredicate<N> setColumnNames(N... columnNames) {
    return setColumnNames(Arrays.asList(columnNames));
  }

  /**
   * Provide a complete copy of this HSlicePredicate. We go through the setter
   * methods to correctly flip the state. Thus, if you set a start/end and
   * have not set a count, this will be in an incomplete state.
   * @return
   */
  @Override
  public HSlicePredicate<N> clone() {
    HSlicePredicate<N> pred = new HSlicePredicate(columnNameSerializer);
    if ( this.predicateType == PredicateType.ColumnNames ) {
      pred.setColumnNames(columnNames);
    } else {
      pred.setStartOn(start);
      pred.setEndOn(finish);
      pred.setReversed(reversed);
      if ( countSet ) {
       pred.setCount(count);
      }
    }
    return pred;
  }

  public HSlicePredicate<N> addColumnName(N columnName) {
    if ( columnNames == null )
      columnNames = new ArrayList<N>();
    columnNames.add(columnName);
    predicateType = PredicateType.ColumnNames;
    return this;
  }
  
  /**
   * Same as varargs signature, except we take a collection
   *
   * @param columnNames
   *          a list of column names
   */
  public HSlicePredicate<N> setColumnNames(Collection<N> columnNames) {
    this.columnNames = columnNames;
    predicateType = PredicateType.ColumnNames;
    return this;
  }

  /**
   * Allows the use of returning just the keys. This avoids de-serialization of row data
   * and can be a huge optimization in some use cases
   *
   */
  public HSlicePredicate<N> setKeysOnlyPredicate() {
    this.columnNames = new ArrayList<N>();
    predicateType = PredicateType.ColumnNames;
    return this;
  }

  /**
   * Set the columnName on which we will start. 
   * Switches to {@link PredicateType#Range} 
   */
  public HSlicePredicate<N> setStartOn(N start) {
    this.start = start;
    predicateType = PredicateType.Range;
    return this;
  }
  
  /**
   * Set the columnName on which we will end. 
   * Switches to {@link PredicateType#Range} 
   */
  public HSlicePredicate<N> setEndOn(N finish) {
    this.finish = finish;
    predicateType = PredicateType.Range;
    return this;
  }
  
  /**
   * Set the number of columns to return for this slice
   * Switches to {@link PredicateType#Range} 
   */
  public HSlicePredicate<N> setCount(int count) {
    this.count = count;
    countSet = true;
    predicateType = PredicateType.Range;
    return this;
  }

  /**
   * Sets the return order of the columns to be reversed. 
   * NOTE: this is slightly less efficient than reading in comparator order. 
   * Switches to {@link PredicateType#Range} 
   */
  public HSlicePredicate<N> setReversed(boolean reversed) {
    this.reversed = reversed;
    predicateType = PredicateType.Range;
    return this;
  }
  
  
  
  
  /**
   * Set a predicate of start/finish to retrieve a list of columns in this range.
   * Either start and or finish can be null which will toggle the underlying predicate to
   * use an empty byte[]
   * @param start
   *          Start key
   * @param finish
   *          End key
   * @param reversed
   * @param count
   * @return
   */
  public HSlicePredicate<N> setRange(N start, N finish, boolean reversed, int count) {
    this.start = start;
    this.finish = finish;
    this.reversed = reversed;
    this.count = count;
    countSet = true;
    predicateType = PredicateType.Range;
    return this;
  }

  /**
   * Short-hand form of the four argument version above. We use 'false' and {@link #DEF_COUNT}
   * as the 'reversed' and 'count' parameters respectively (matching the defaults in the Thrift
   * SlicePredicate struct).
   * @param start
   * @param finish
   * @return
   */
  public HSlicePredicate<N> setRange(N start, N finish) {
    return setRange(start, finish, false, DEF_COUNT);
  }

  /**
   * Convienice method to say: 'give me just the next countAsPageSize columns'. Best used
   * for paging over results
   * @param countAsPageSize
   * @return
   */
  public HSlicePredicate<N> setRangeForCountOnly(int countAsPageSize) {
    return setRange(null,null,false,countAsPageSize);
  }

  /**
   * Returns a copy of this predicate, except we use startWith as the start and set the finish to
   * null. This method should be used by taking the last result from the previous slice execution
   * and using that as the 'startWith' argument. It is up to the caller to remove the first result
   * as it will be a duplicate of the previous slice's end result. (In a truly distributed system,
   * there is no way to know what you have until you have it). 
   *
   * This pattern is used to page over columns at the count size.
   * Consider using {@link me.prettyprint.cassandra.service.ColumnSliceIterator} to encapsulate
   * this functionality.
   * @return
   */
  public HSlicePredicate<N> getForNextPage(N startWith) {
    if ( this.predicateType == PredicateType.ColumnNames ) {
      throw new IllegalArgumentException("Attemted to construct a 'next page' predicate with column names defined. You can only do this with a range predicate.");
    }
    HSlicePredicate<N> pred = clone();
    pred.setStartOn(startWith);
    pred.setEndOn(null);
    return pred;
  }

  public Collection<N> getColumnNames() {
    return Collections.unmodifiableCollection(columnNames);
  }

  /**
   * Will throw a runtime exception if neither columnsNames nor count were set.
   * @return
   */
  public SlicePredicate toThrift() {
    SlicePredicate pred = new SlicePredicate();

    switch (predicateType) {
    case ColumnNames:
      if (columnNames == null ) {
        return null;
      }
      pred.setColumn_names(toThriftColumnNames(columnNames));
      break;
    case Range:
      Assert.isTrue(countSet, "Count was not set, neither were column-names set, can't execute");
      SliceRange range = new SliceRange(findBytes(start), findBytes(finish), reversed, count);
      pred.setSlice_range(range);
      break;
    case Unknown:
    default:
      throw new HectorException(
          "Neither column names nor range were set, this is an invalid slice predicate");
    }
    return pred;
  }

  private ByteBuffer findBytes(N val) {
    ByteBuffer valBytes;
    if (val == null) {
      valBytes =  EMPTY_BYTE_BUFFER;
    } else {
      valBytes = columnNameSerializer.toByteBuffer(val);
    }
    return valBytes;
  }

  private List<ByteBuffer> toThriftColumnNames(Collection<N> clms) {
    List<ByteBuffer> ret = new ArrayList<ByteBuffer>(clms.size());
    for (N name : clms) {
      ret.add(columnNameSerializer.toByteBuffer(name));
    }
    return ret;
  }

  @Override
  public String toString() {
    return String.format("HSlicePredicate(%s)", predicateType == PredicateType.ColumnNames ? columnNames : formatPredicate());    
  }
  
  private String formatPredicate() {
    return String.format("start:[%s],end:[%s],count:%d,reversed:%b", 
        start != null ? start.toString() : "''",
            finish != null ? finish.toString() : "''",
                count, reversed);
  }
}
