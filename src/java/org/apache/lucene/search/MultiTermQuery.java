package org.apache.lucene.search;

/**
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.PriorityQueue;

import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermRef;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queryParser.QueryParser; // for javadoc
import org.apache.lucene.util.Attribute;
import org.apache.lucene.util.AttributeImpl;

/**
 * An abstract {@link Query} that matches documents
 * containing a subset of terms provided by a {@link
 * FilteredTermEnum} enumeration.
 *
 * <p>This query cannot be used directly; you must subclass
 * it and define {@link #getEnum} to provide a {@link
 * FilteredTermEnum} that iterates through the terms to be
 * matched.
 *
 * <p><b>NOTE</b>: if {@link #setRewriteMethod} is either
 * {@link #CONSTANT_SCORE_BOOLEAN_QUERY_REWRITE} or {@link
 * #SCORING_BOOLEAN_QUERY_REWRITE}, you may encounter a
 * {@link BooleanQuery.TooManyClauses} exception during
 * searching, which happens when the number of terms to be
 * searched exceeds {@link
 * BooleanQuery#getMaxClauseCount()}.  Setting {@link
 * #setRewriteMethod} to {@link #CONSTANT_SCORE_FILTER_REWRITE}
 * prevents this.
 *
 * <p>The recommended rewrite method is {@link
 * #CONSTANT_SCORE_AUTO_REWRITE_DEFAULT}: it doesn't spend CPU
 * computing unhelpful scores, and it tries to pick the most
 * performant rewrite method given the query. If you
 * need scoring (like {@link FuzzyQuery}, use
 * {@link #TOP_TERMS_SCORING_BOOLEAN_REWRITE} which uses
 * a priority queue to only collect competitive terms
 * and not hit this limitation.
 *
 * Note that {@link QueryParser} produces
 * MultiTermQueries using {@link
 * #CONSTANT_SCORE_AUTO_REWRITE_DEFAULT} by default.
 */
public abstract class MultiTermQuery extends Query {
  protected final String field;
  protected RewriteMethod rewriteMethod = CONSTANT_SCORE_AUTO_REWRITE_DEFAULT;
  transient int numberOfTerms = 0;
  
  /** Add this {@link Attribute} to a {@link TermsEnum} returned by {@link #getTermsEnum}
   * and update the boost on each returned term. This enables to control the boost factor
   * for each matching term in {@link #SCORING_BOOLEAN_QUERY_REWRITE} or
   * {@link TOP_TERMS_SCORING_BOOLEAN_QUERY_REWRITE} mode.
   * {@link FuzzyQuery} is using this to take the edit distance into account.
   */
  public static interface BoostAttribute extends Attribute {
    /** Sets the boost in this attribute */
    public void setBoost(float boost);
    /** Retrieves the boost, default is {@code 1.0f}. */
    public float getBoost();
  }

  /** Implementation class for {@link BoostAttribute}. */
  public static class BoostAttributeImpl extends AttributeImpl implements BoostAttribute {
    private float boost = 1.0f;
  
    public void setBoost(float boost) {
      this.boost = boost;
    }
    
    public float getBoost() {
      return boost;
    }

    @Override
    public void clear() {
      boost = 1.0f;
    }

    @Override
    public boolean equals(Object other) {
      if (this == other)
        return true;
      if (other instanceof BoostAttributeImpl)
        return ((BoostAttributeImpl) other).boost == boost;
      return false;
    }

    @Override
    public int hashCode() {
      return Float.floatToIntBits(boost);
    }
    
    @Override
    public void copyTo(AttributeImpl target) {
      ((BoostAttribute) target).setBoost(boost);
    }
  }

  /** Abstract class that defines how the query is rewritten. */
  public static abstract class RewriteMethod implements Serializable {
    public abstract Query rewrite(IndexReader reader, MultiTermQuery query) throws IOException;
  }

  private static final class ConstantScoreFilterRewrite extends RewriteMethod {
    @Override
    public Query rewrite(IndexReader reader, MultiTermQuery query) {
      Query result = new ConstantScoreQuery(new MultiTermQueryWrapperFilter<MultiTermQuery>(query));
      result.setBoost(query.getBoost());
      return result;
    }

    // Make sure we are still a singleton even after deserializing
    protected Object readResolve() {
      return CONSTANT_SCORE_FILTER_REWRITE;
    }
  }

  /** A rewrite method that first creates a private Filter,
   *  by visiting each term in sequence and marking all docs
   *  for that term.  Matching documents are assigned a
   *  constant score equal to the query's boost.
   * 
   *  <p> This method is faster than the BooleanQuery
   *  rewrite methods when the number of matched terms or
   *  matched documents is non-trivial. Also, it will never
   *  hit an errant {@link BooleanQuery.TooManyClauses}
   *  exception.
   *
   *  @see #setRewriteMethod */
  public final static RewriteMethod CONSTANT_SCORE_FILTER_REWRITE = new ConstantScoreFilterRewrite();

  private abstract static class BooleanQueryRewrite extends RewriteMethod {
  
    protected final int collectTerms(IndexReader reader, MultiTermQuery query, TermCollector collector) throws IOException {
      final TermsEnum termsEnum = query.getTermsEnum(reader);
      if (termsEnum != null) {
        final BoostAttribute boostAtt =
          termsEnum.attributes().addAttribute(BoostAttribute.class);
        if (query.field == null)
          throw new NullPointerException("If you implement getTermsEnum(), you must specify a non-null field in the constructor of MultiTermQuery.");
        int count = 0;
        TermRef term;
        final Term placeholderTerm = new Term(query.field);
        while ((term = termsEnum.next()) != null) {
          if (collector.collect(placeholderTerm.createTerm(term.toString()), boostAtt.getBoost())) {
            count++;
          } else {
            break;
          }
        }
        return count;
      } else {
        // deprecated case
        final FilteredTermEnum enumerator = query.getEnum(reader);
        int count = 0;
        try {
          do {
            Term t = enumerator.term();
            if (t != null) {
              if (collector.collect(t, enumerator.difference())) {
                count++;
              } else {
                break;
              }
            }
          } while (enumerator.next());    
        } finally {
          enumerator.close();
        }
        return count;
      }
    }
    
    protected interface TermCollector {
      /** return false to stop collecting */
      boolean collect(Term t, float boost) throws IOException;
    }
    
  }
  
  private static class ScoringBooleanQueryRewrite extends BooleanQueryRewrite {
    @Override
    public Query rewrite(final IndexReader reader, final MultiTermQuery query) throws IOException {
      final BooleanQuery result = new BooleanQuery(true);
      query.incTotalNumberOfTerms(collectTerms(reader, query, new TermCollector() {
        public boolean collect(Term t, float boost) {
          TermQuery tq = new TermQuery(t); // found a match
          tq.setBoost(query.getBoost() * boost); // set the boost
          result.add(tq, BooleanClause.Occur.SHOULD); // add to query
          return true;
        }
      }));
      return result;
    }

    // Make sure we are still a singleton even after deserializing
    protected Object readResolve() {
      return SCORING_BOOLEAN_QUERY_REWRITE;
    }
  }

  /** A rewrite method that first translates each term into
   *  {@link BooleanClause.Occur#SHOULD} clause in a
   *  BooleanQuery, and keeps the scores as computed by the
   *  query.  Note that typically such scores are
   *  meaningless to the user, and require non-trivial CPU
   *  to compute, so it's almost always better to use {@link
   *  #CONSTANT_SCORE_AUTO_REWRITE_DEFAULT} instead.
   *
   *  <p><b>NOTE</b>: This rewrite method will hit {@link
   *  BooleanQuery.TooManyClauses} if the number of terms
   *  exceeds {@link BooleanQuery#getMaxClauseCount}.
   *
   *  @see #setRewriteMethod */
  public final static RewriteMethod SCORING_BOOLEAN_QUERY_REWRITE = new ScoringBooleanQueryRewrite();

  private static final class TopTermsScoringBooleanQueryRewrite extends BooleanQueryRewrite {
    @Override
    public Query rewrite(IndexReader reader, MultiTermQuery query) throws IOException {
      final int maxSize = BooleanQuery.getMaxClauseCount();
      final PriorityQueue<ScoreTerm> stQueue = new PriorityQueue<ScoreTerm>();
      collectTerms(reader, query, new TermCollector() {
        public boolean collect(Term t, float boost) {
          // ignore uncompetetive hits
          if (stQueue.size() >= maxSize && boost <= stQueue.peek().boost)
            return true;
          // add new entry in PQ
          st.term = t;
          st.boost = boost;
          stQueue.offer(st);
          // possibly drop entries from queue
          st = (stQueue.size() > maxSize) ? stQueue.poll() : new ScoreTerm();
          return true;
        }
        
        // reusable instance
        private ScoreTerm st = new ScoreTerm();
      });
      
      final BooleanQuery bq = new BooleanQuery(true);
      for (final ScoreTerm st : stQueue) {
        TermQuery tq = new TermQuery(st.term);    // found a match
        tq.setBoost(query.getBoost() * st.boost); // set the boost
        bq.add(tq, BooleanClause.Occur.SHOULD);   // add to query
      }
      query.incTotalNumberOfTerms(bq.clauses().size());
      return bq;
    }

    // Make sure we are still a singleton even after deserializing
    protected Object readResolve() {
      return TOP_TERMS_SCORING_BOOLEAN_REWRITE;
    }
  
    private static class ScoreTerm implements Comparable<ScoreTerm> {
      public Term term;
      public float boost;
      
      public int compareTo(ScoreTerm other) {
        if (this.boost == other.boost)
          return other.term.compareTo(this.term);
        else
          return Float.compare(this.boost, other.boost);
      }
    }
  }
  
  /** A rewrite method that first translates each term into
   *  {@link BooleanClause.Occur#SHOULD} clause in a
   *  BooleanQuery, and keeps the scores as computed by the
   *  query.
   *
   * <p>This rewrite mode only uses the top scoring terms
   * so it will not overflow the boolean max clause count.
   * It is the default rewrite mode for {@link FuzzyQuery}.
   *
   *  @see #setRewriteMethod */
  public final static RewriteMethod TOP_TERMS_SCORING_BOOLEAN_REWRITE = new TopTermsScoringBooleanQueryRewrite();

  private static class ConstantScoreBooleanQueryRewrite extends ScoringBooleanQueryRewrite implements Serializable {
    @Override
    public Query rewrite(IndexReader reader, MultiTermQuery query) throws IOException {
      Query result = super.rewrite(reader, query);
      assert result instanceof BooleanQuery;
      // nocommit: if empty boolean query return NullQuery
      if (!((BooleanQuery) result).clauses().isEmpty()) {
        // strip the scores off
        result = new ConstantScoreQuery(new QueryWrapperFilter(result));
        result.setBoost(query.getBoost());
      }
      return result;
    }

    // Make sure we are still a singleton even after deserializing
    @Override
    protected Object readResolve() {
      return CONSTANT_SCORE_BOOLEAN_QUERY_REWRITE;
    }
  }

  /** Like {@link #SCORING_BOOLEAN_QUERY_REWRITE} except
   *  scores are not computed.  Instead, each matching
   *  document receives a constant score equal to the
   *  query's boost.
   * 
   *  <p><b>NOTE</b>: This rewrite method will hit {@link
   *  BooleanQuery.TooManyClauses} if the number of terms
   *  exceeds {@link BooleanQuery#getMaxClauseCount}.
   *
   *  @see #setRewriteMethod */
  public final static RewriteMethod CONSTANT_SCORE_BOOLEAN_QUERY_REWRITE = new ConstantScoreBooleanQueryRewrite();


  /** A rewrite method that tries to pick the best
   *  constant-score rewrite method based on term and
   *  document counts from the query.  If both the number of
   *  terms and documents is small enough, then {@link
   *  #CONSTANT_SCORE_BOOLEAN_QUERY_REWRITE} is used.
   *  Otherwise, {@link #CONSTANT_SCORE_FILTER_REWRITE} is
   *  used.
   */
  public static class ConstantScoreAutoRewrite extends BooleanQueryRewrite {

    // Defaults derived from rough tests with a 20.0 million
    // doc Wikipedia index.  With more than 350 terms in the
    // query, the filter method is fastest:
    public static int DEFAULT_TERM_COUNT_CUTOFF = 350;

    // If the query will hit more than 1 in 1000 of the docs
    // in the index (0.1%), the filter method is fastest:
    public static double DEFAULT_DOC_COUNT_PERCENT = 0.1;

    private int termCountCutoff = DEFAULT_TERM_COUNT_CUTOFF;
    private double docCountPercent = DEFAULT_DOC_COUNT_PERCENT;

    /** If the number of terms in this query is equal to or
     *  larger than this setting then {@link
     *  #CONSTANT_SCORE_FILTER_REWRITE} is used. */
    public void setTermCountCutoff(int count) {
      termCountCutoff = count;
    }

    /** @see #setTermCountCutoff */
    public int getTermCountCutoff() {
      return termCountCutoff;
    }

    /** If the number of documents to be visited in the
     *  postings exceeds this specified percentage of the
     *  maxDoc() for the index, then {@link
     *  #CONSTANT_SCORE_FILTER_REWRITE} is used.
     *  @param percent 0.0 to 100.0 */
    public void setDocCountPercent(double percent) {
      docCountPercent = percent;
    }

    /** @see #setDocCountPercent */
    public double getDocCountPercent() {
      return docCountPercent;
    }

    @Override
    public Query rewrite(final IndexReader reader, final MultiTermQuery query) throws IOException {

      // Get the enum and start visiting terms.  If we
      // exhaust the enum before hitting either of the
      // cutoffs, we use ConstantBooleanQueryRewrite; else,
      // ConstantFilterRewrite:
      final int docCountCutoff = (int) ((docCountPercent / 100.) * reader.maxDoc());
      final int termCountLimit = Math.min(BooleanQuery.getMaxClauseCount(), termCountCutoff);

      final CutOffTermCollector col = new CutOffTermCollector(reader, docCountCutoff, termCountLimit);
      collectTerms(reader, query, col);
      
      if (col.hasCutOff) {
        return CONSTANT_SCORE_FILTER_REWRITE.rewrite(reader, query);
      } else {
        final Query result;
        if (col.pendingTerms.isEmpty()) {
          result = new BooleanQuery(true);
        } else {
          BooleanQuery bq = new BooleanQuery(true);
          for(Term term : col.pendingTerms) {
            TermQuery tq = new TermQuery(term);
            bq.add(tq, BooleanClause.Occur.SHOULD);
          }
          // Strip scores
          result = new ConstantScoreQuery(new QueryWrapperFilter(bq));
          result.setBoost(query.getBoost());
        }
        query.incTotalNumberOfTerms(col.pendingTerms.size());
        return result;
      }
    }
    
    private static final class CutOffTermCollector implements TermCollector {
      CutOffTermCollector(IndexReader reader, int docCountCutoff, int termCountLimit) {
        this.reader = reader;
        this.docCountCutoff = docCountCutoff;
        this.termCountLimit = termCountLimit;
      }
    
      public boolean collect(Term t, float boost) throws IOException {
        pendingTerms.add(t);
        if (pendingTerms.size() >= termCountLimit || docVisitCount >= docCountCutoff) {
          hasCutOff = true;
          return false;
        }
        // Loading the TermInfo from the terms dict here
        // should not be costly, because 1) the
        // query/filter will load the TermInfo when it
        // runs, and 2) the terms dict has a cache:
        // @deprecated: in 4.0 use TermRef for collectTerms()
        docVisitCount += reader.docFreq(t);
        return true;
      }
      
      int docVisitCount = 0;
      boolean hasCutOff = false;
      
      final IndexReader reader;
      final int docCountCutoff, termCountLimit;
      final ArrayList<Term> pendingTerms = new ArrayList<Term>();
    }

    @Override
    public int hashCode() {
      final int prime = 1279;
      return (int) (prime * termCountCutoff + Double.doubleToLongBits(docCountPercent));
    }

    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;

      ConstantScoreAutoRewrite other = (ConstantScoreAutoRewrite) obj;
      if (other.termCountCutoff != termCountCutoff) {
        return false;
      }

      if (Double.doubleToLongBits(other.docCountPercent) != Double.doubleToLongBits(docCountPercent)) {
        return false;
      }
      
      return true;
    }
  }

  /** Read-only default instance of {@link
   *  ConstantScoreAutoRewrite}, with {@link
   *  ConstantScoreAutoRewrite#setTermCountCutoff} set to
   *  {@link
   *  ConstantScoreAutoRewrite#DEFAULT_TERM_COUNT_CUTOFF}
   *  and {@link
   *  ConstantScoreAutoRewrite#setDocCountPercent} set to
   *  {@link
   *  ConstantScoreAutoRewrite#DEFAULT_DOC_COUNT_PERCENT}.
   *  Note that you cannot alter the configuration of this
   *  instance; you'll need to create a private instance
   *  instead. */
  public final static RewriteMethod CONSTANT_SCORE_AUTO_REWRITE_DEFAULT = new ConstantScoreAutoRewrite() {
    @Override
    public void setTermCountCutoff(int count) {
      throw new UnsupportedOperationException("Please create a private instance");
    }

    @Override
    public void setDocCountPercent(double percent) {
      throw new UnsupportedOperationException("Please create a private instance");
    }

    // Make sure we are still a singleton even after deserializing
    protected Object readResolve() {
      return CONSTANT_SCORE_AUTO_REWRITE_DEFAULT;
    }
  };

  /**
   * Constructs a query matching terms that cannot be represented with a single
   * Term.
   */
  public MultiTermQuery(final String field) {
    this.field = field;
  }

  /**
   * Constructs a query matching terms that cannot be represented with a single
   * Term.
   * @deprecated Use {@link #MultiTermQuery(String)}, as the flex branch can
   * only work on one field per terms enum. If you override
   * {@link #getTermsEnum(IndexReader)}, you cannot use this ctor.
   */
  @Deprecated
  public MultiTermQuery() {
    this(null);
  }

  /** Returns the field name for this query */
  public final String getField() { return field; }

  /** Construct the enumeration to be used, expanding the
   * pattern term.
   * @deprecated Please override {@link #getTermsEnum} instead */
  @Deprecated
  protected FilteredTermEnum getEnum(IndexReader reader) throws IOException {
    return null;
  }

  /** Construct the enumeration to be used, expanding the
   *  pattern term.  This method must return null if no
   *  terms fall in the range; else, it must return a
   *  TermsEnum already positioned to the first matching
   *  term.
   *
   *  nocommit in 3.x this will become abstract  */
  protected TermsEnum getTermsEnum(IndexReader reader) throws IOException {
    return null;
  }

  /**
   * Expert: Return the number of unique terms visited during execution of the query.
   * If there are many of them, you may consider using another query type
   * or optimize your total term count in index.
   * <p>This method is not thread safe, be sure to only call it when no query is running!
   * If you re-use the same query instance for another
   * search, be sure to first reset the term counter
   * with {@link #clearTotalNumberOfTerms}.
   * <p>On optimized indexes / no MultiReaders, you get the correct number of
   * unique terms for the whole index. Use this number to compare different queries.
   * For non-optimized indexes this number can also be achieved in
   * non-constant-score mode. In constant-score mode you get the total number of
   * terms seeked for all segments / sub-readers.
   * @see #clearTotalNumberOfTerms
   */
  public int getTotalNumberOfTerms() {
    return numberOfTerms;
  }
  
  /**
   * Expert: Resets the counting of unique terms.
   * Do this before executing the query/filter.
   * @see #getTotalNumberOfTerms
   */
  public void clearTotalNumberOfTerms() {
    numberOfTerms = 0;
  }
  
  protected void incTotalNumberOfTerms(int inc) {
    numberOfTerms += inc;
  }

  @Override
  public Query rewrite(IndexReader reader) throws IOException {
    return rewriteMethod.rewrite(reader, this);
  }

  /**
   * @see #setRewriteMethod
   */
  public RewriteMethod getRewriteMethod() {
    return rewriteMethod;
  }

  /**
   * Sets the rewrite method to be used when executing the
   * query.  You can use one of the four core methods, or
   * implement your own subclass of {@link RewriteMethod}. */
  public void setRewriteMethod(RewriteMethod method) {
    rewriteMethod = method;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + Float.floatToIntBits(getBoost());
    result = prime * result + rewriteMethod.hashCode();
    if (field != null) result = prime * result + field.hashCode();
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    MultiTermQuery other = (MultiTermQuery) obj;
    if (Float.floatToIntBits(getBoost()) != Float.floatToIntBits(other.getBoost()))
      return false;
    if (!rewriteMethod.equals(other.rewriteMethod)) {
      return false;
    }
    return (other.field == null ? field == null : other.field.equals(field));
  }
 
}
