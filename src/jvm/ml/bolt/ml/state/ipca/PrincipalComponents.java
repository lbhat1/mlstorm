package bolt.ml.state.ipca;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import org.ejml.data.DenseMatrix64F;
import org.ejml.factory.DecompositionFactory;
import org.ejml.factory.SingularValueDecomposition;
import org.ejml.ops.CommonOps;
import org.ejml.ops.NormOps;
import org.ejml.ops.SingularOps;
import spout.dbutils.SensorDbUtils;
import storm.trident.state.State;

import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;

/**
 * User: lbhat@damsl
 * Date: 12/13/13
 * Time: 9:08 PM
 */
public class PrincipalComponents implements State {

    private Map<String, Double>              currentSensors;
    private Cache<Long, Map<String, Double>> windowTimesteps;

    // principle component subspace is stored in the rows
    private DenseMatrix64F V_t;

    // how many principal components are used
    private int numPrincipalComponents;

    // state partition information
    private int localPartition, numPartition, pcaRowWidth;

    // where the data is stored
    private DenseMatrix64F A = new DenseMatrix64F(1, 1);
    private int sampleIndex;

    // mean values of each element across all the samples
    double mean[];

    public PrincipalComponents (int elementsInSample, int localPartition, int numPartitions) {
        this.localPartition = localPartition;
        this.numPartition = numPartitions;
        this.pcaRowWidth = elementsInSample;

        //setup(numSamples, elementsInSample);
        currentSensors = new ConcurrentSkipListMap<String, Double>();
        windowTimesteps = CacheBuilder.newBuilder().expireAfterAccess(24, TimeUnit.HOURS).maximumSize(elementsInSample).build();
    }

    /**
     * Must be called before any other functions. Declares and sets up internal data structures.
     *
     * @param numSamples Number of samples that will be processed.
     * @param sampleSize Number of elements in each sample.
     */
    public void setup (int numSamples, int sampleSize) {
        mean = new double[sampleSize];
        A.reshape(numSamples, sampleSize, false);
        sampleIndex = 0;
        numPrincipalComponents = -1;
    }

    /**
     * Adds a new sample of the raw data to internal data structure for later processing.  All the samples
     * must be added before computeBasis is called.
     *
     * @param sampleData Sample from original raw data.
     */
    public void addSample (double[] sampleData) {
        if (A.getNumCols() != sampleData.length)
            throw new IllegalArgumentException("Unexpected sample size");
        if (sampleIndex >= A.getNumRows())
            throw new IllegalArgumentException("Too many samples");

        for (int i = 0; i < sampleData.length; i++) {
            A.set(sampleIndex, i, sampleData[i]);
        }
        sampleIndex++;
    }

    /**
     * Adds a new sample of the raw data to internal data structure for later processing.  All the samples
     * must be added before computeBasis is called.
     *
     * @param sampleData Sample from original raw data.
     */
    public void addSampleUnsafe (double[] sampleData, int setIndex) throws IllegalArgumentException {
        if (A.getNumCols() != sampleData.length)
            throw new IllegalArgumentException("Unexpected sample size");

        for (int i = 0; i < sampleData.length; i++) {
            A.set(setIndex, i, sampleData[i]);
        }
    }

    public void addSample (double[][] sampleData) {
        for (int i = 0; i < sampleData.length; i++)
            try {
                addSample(sampleData[i]);
            } catch ( IllegalArgumentException e ) {
                addSampleUnsafe(sampleData[i], sampleIndex);
            }
    }

    /**
     * Computes a basis (the principal components) from the most dominant eigenvectors.
     *
     * @param numComponents Number of vectors it will use to describe the data.  Typically much
     *                      smaller than the number of elements in the input vector.
     */
    public void computeBasis (int numComponents) {
        if (numComponents > A.getNumCols())
            throw new IllegalArgumentException("More components requested that the data's length.");
        if (sampleIndex != A.getNumRows())
            throw new IllegalArgumentException("Not all the data has been added");
        if (numComponents > sampleIndex)
            throw new IllegalArgumentException("More data needed to compute the desired number of components");

        this.numPrincipalComponents = numComponents;

        // compute the mean of all the samples
        for (int i = 0; i < A.getNumRows(); i++) {
            for (int j = 0; j < mean.length; j++) {
                mean[j] += A.get(i, j);
            }
        }
        for (int j = 0; j < mean.length; j++) {
            mean[j] /= A.getNumRows();
        }

        // subtract the mean from the original data
        for (int i = 0; i < A.getNumRows(); i++) {
            for (int j = 0; j < mean.length; j++) {
                A.set(i, j, A.get(i, j) - mean[j]);
            }
        }

        // Compute SVD and save time by not computing U
        SingularValueDecomposition<DenseMatrix64F> svd =
                DecompositionFactory.svd(A.numRows, A.numCols, false, true, false);
        if (!svd.decompose(A))
            throw new RuntimeException("SVD failed");

        V_t = svd.getV(null, true);
        DenseMatrix64F W = svd.getW(null);

        // Singular values are in an arbitrary order initially
        SingularOps.descendingOrder(null, false, W, V_t, true);

        // strip off unneeded components and find the basis
        V_t.reshape(numComponents, mean.length, true);
    }

    /**
     * Returns a vector from the PCA's basis.
     *
     * @param which Which component's vector is to be returned.
     * @return Vector from the PCA basis.
     */
    public double[] getBasisVector (int which) {
        if (which < 0 || which >= numPrincipalComponents)
            throw new IllegalArgumentException("Invalid component");

        DenseMatrix64F v = new DenseMatrix64F(1, A.numCols);
        CommonOps.extract(V_t, which, which + 1, 0, A.numCols, v, 0, 0);

        return v.data;
    }

    /**
     * Converts a vector from sample space into eigen space.
     *
     * @param sampleData Sample space data.
     * @return Eigen space projection.
     */
    public double[] sampleToEigenSpace (double[] sampleData) {
        if (sampleData.length != A.getNumCols())
            throw new IllegalArgumentException("Unexpected sample length");
        DenseMatrix64F mean = DenseMatrix64F.wrap(A.getNumCols(), 1, this.mean);

        DenseMatrix64F s = new DenseMatrix64F(A.getNumCols(), 1, true, sampleData);
        DenseMatrix64F r = new DenseMatrix64F(numPrincipalComponents, 1);

        CommonOps.sub(s, mean, s);

        CommonOps.mult(V_t, s, r);

        return r.data;
    }

    /**
     * Converts a vector from eigen space into sample space.
     *
     * @param eigenData Eigen space data.
     * @return Sample space projection.
     */
    public double[] eigenToSampleSpace (double[] eigenData) {
        if (eigenData.length != numPrincipalComponents)
            throw new IllegalArgumentException("Unexpected sample length");

        DenseMatrix64F s = new DenseMatrix64F(A.getNumCols(), 1);
        DenseMatrix64F r = DenseMatrix64F.wrap(numPrincipalComponents, 1, eigenData);

        CommonOps.multTransA(V_t, r, s);

        DenseMatrix64F mean = DenseMatrix64F.wrap(A.getNumCols(), 1, this.mean);
        CommonOps.add(s, mean, s);

        return s.data;
    }


    /**
     * <p>
     * The membership error for a sample.  If the error is less than a threshold then
     * it can be considered a member.  The threshold's value depends on the data set.
     * </p>
     * <p>
     * The error is computed by projecting the sample into eigenspace then projecting
     * it back into sample space and
     * </p>
     *
     * @param sampleA The sample whose membership status is being considered.
     * @return Its membership error.
     */
    public double errorMembership (double[] sampleA) {
        double[] eig = sampleToEigenSpace(sampleA);
        double[] reproj = eigenToSampleSpace(eig);


        double total = 0;
        for (int i = 0; i < reproj.length; i++) {
            double d = sampleA[i] - reproj[i];
            total += d * d;
        }

        return Math.sqrt(total);
    }

    /**
     * Computes the dot product of each basis vector against the sample.  Can be used as a measure
     * for membership in the training sample set.  High values correspond to a better fit.
     *
     * @param sample Sample of original data.
     * @return Higher value indicates it is more likely to be a member of input dataset.
     */
    public double response (double[] sample) {
        if (sample.length != A.numCols)
            throw new IllegalArgumentException("Expected input vector to be in sample space");

        DenseMatrix64F dots = new DenseMatrix64F(numPrincipalComponents, 1);
        DenseMatrix64F s = DenseMatrix64F.wrap(A.numCols, 1, sample);

        CommonOps.mult(V_t, s, dots);
        return NormOps.normF(dots);
    }

    /**
     * Before we start a batch, storm tells us which "storm transaction" we are going to commit
     *
     * @param txid
     */
    @Override
    public void beginCommit (final Long txid) {}

    /**
     * Nothing fancy. We push this sensor reading to the time-series window
     *
     * @param txId
     */
    @Override
    public synchronized void commit (final Long txId) {
        Set<String> sensorNames = currentSensors.keySet();

        if (currentSensors.size() == SensorDbUtils.NO_OF_SENSORS)
            windowTimesteps.put(txId, getUpdatedFeatureVectors(true));

        if (windowTimesteps.size() < pcaRowWidth)
            return;

        setup(SensorDbUtils.NO_OF_SENSORS, (int) windowTimesteps.size());
        // Read all the rows and add them to the matrix
        for (String sensorName : sensorNames) {
            double[] row = new double[((int) windowTimesteps.size())];
            Iterator<Map<String, Double>> iterator = windowTimesteps.asMap().values().iterator();
            int i = 0;
            while (iterator.hasNext()) {
                final Map<String, Double> timeStep = iterator.next();
                row[i++] = timeStep.get(sensorName);
            }
            addSample(row);
        }
        computeBasis(5);
        A.zero();
    }

    public Map<String, Double> getFeatureVectors () {return getUpdatedFeatureVectors(false);}

    public synchronized Map<String, Double> getUpdatedFeatureVectors (final boolean updateFeatureCache) {
        final Map<String, Double> oldSensors = new ConcurrentSkipListMap<String, Double>();
        oldSensors.putAll(currentSensors);
        if (updateFeatureCache) currentSensors.clear();
        return oldSensors;
    }

    /**
     * Returns the local partition Id
     * @return
     */
    public int getLocalPartition () {
        return localPartition;
    }

    /**
     * Returns the total number of partitions persisting this state
     * @return
     */
    public int getNumPartition () {
        return numPartition;
    }
}
