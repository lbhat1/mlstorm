package bolt.ml.state.weka.classifier;

import bolt.ml.state.weka.BaseWekaState;
import bolt.ml.state.weka.utils.WekaUtils;
import weka.classifiers.Classifier;
import weka.core.Instance;
import weka.core.Instances;

import java.util.Collection;

/**
 * Created by lbhat@DaMSl on 2/10/14.
 * <p/>
 * Copyright {2013} {Lakshmisha Bhat}
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
public class BinaryClassifierState extends BaseWekaState {

    private Classifier classifier;
    private final Object lock;

    /**
     * Construct the State representation for any weka based learning algorithm
     *
     * @param windowSize the size of the sliding window (cache size)
     */
    public BinaryClassifierState(String classifier, int windowSize) {
        super(windowSize);
        this.classifier = WekaUtils.makeClassifier(classifier);
        lock = new Object();
    }

    @Override
    public double predict(Instance testInstance) throws Exception {
        assert testInstance != null;
        synchronized (lock) {
            return classifier.classifyInstance(testInstance);
        }
    }

    @Override
    protected void postUpdate() {

    }

    @Override
    protected void emptyDataset() {
        synchronized (lock){  dataset.clear(); }
    }

    @Override
    protected void createDataSet() throws Exception {
        // Our aim is to create a singleton dataset which will be reused by all trainingInstances
        if (this.dataset != null) return;

        // hack to obtain the feature set length
        Collection<double[]> features = this.featureVectorsInWindow.values();
        for (double[] some : features) {
            loadWekaAttributes(some);
            break;
        }

        // we are now ready to create a training dataset metadata
        dataset = new Instances("training", this.wekaAttributes, this.windowSize);

    }

    @Override
    protected void loadWekaAttributes(double[] features) {
        if (this.wekaAttributes == null) {
            this.wekaAttributes = WekaUtils.makeFeatureVectorForBatchClustering(features.length, 2 /* binary classification */);
            this.wekaAttributes.trimToSize();
        }
    }

    @Override
    protected void train() throws Exception {
        synchronized (lock) {
            long startTime = System.currentTimeMillis();
            this.classifier.buildClassifier(dataset);
            long endTime = System.currentTimeMillis();
            this.trainingDuration = (getTrainingDuration() + (endTime - startTime))/2;
        }
    }
}