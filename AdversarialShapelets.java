/*
 * To change this license header, choose License Headers in Project Properties.
 * To change this template file, choose Tools | Templates
 * and open the template in the editor.
 */
package timeseriesweka.classifiers;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import utilities.StatisticalUtilities;
import utilities.InstanceTools;
import static utilities.InstanceTools.fromWekaInstancesArray;
import static utilities.StatisticalUtilities.calculateSigmoid;
//import weka.classifiers.*;
import weka.clusterers.SimpleKMeans;
import weka.core.Capabilities;
import weka.core.Instance;
import weka.core.Instances;
import weka.core.TechnicalInformation;

/**
 *
 * @author a-bailly
 */
public class AdversarialShapelets extends AbstractClassifierWithTrainingData implements ParameterSplittable{
/*
    public TechnicalInformation getTechnicalInformation() {
        TechnicalInformation 	result;
        result = new TechnicalInformation(TechnicalInformation.Type.ARTICLE);
        result.setValue(TechnicalInformation.Field.AUTHOR, "J. Grabocka, N. Schilling, M. Wistuba and L. Schmidt-Thieme");
        result.setValue(TechnicalInformation.Field.TITLE, "Learning Time-Series Shapelets");
        result.setValue(TechnicalInformation.Field.JOURNAL, "Proc. 20th SIGKDD");
        result.setValue(TechnicalInformation.Field.YEAR, "2014");
        return result;
    }
//*/
    boolean suppressOutput = false;
    long seed;

    // length of a time-series
    public int seriesLength;
    // length of shapelet
    public int[] L;
    // number of latent patterns
    public int K;
    // number of classes
    public int C;
    // number of segments
    public int numberOfSegments[];

    int L_min;
    // shapelets
    double Shapelets[][][];
    // classification weights
    double W[][][];
    double biasW[];

    // accumulate the gradients
    double GradHistShapelets[][][];
    double GradHistW[][][];
    double GradHistBiasW[];

    // the regularization parameters
    public double lambdaW=0.01;
    // scales of the shapelet length
    public int R=3;
    public double percentageOfSeriesLength=0.2;
    // the learning rate
    public double eta=0.1;
    // the softmax parameter
    public double alpha=-30;
    // the number of iterations
    public int maxIter=300;
    // the adversarial perturbation
    public double eps_adv = 0.01;

    public Instances trainSet, data_adv;
//    public Instance testSet;
    // time series data and the label
    public double[][] train, classValues_train;
//    public double[] test;

    public List<Double> nominalLabels;

    // structures for storing the precomputed terms
    double D_train[][][][]; //mean square error for each shapelet compared with each shapelet centroid. Formula 20
    double E_train[][][][]; // e^alpha*D_r,i,k,j part of Formula 23.
    double M_train[][][];   //Generalised Soft Minimum. Formula 19.
    double Psi_train[][][]; //Sum 1->j(e^alpha*D_r,i,k,j'). Denominator of Formula 23.
    double sigY_train[][];
    double D_test[][][];
    double E_test[][][];
    double M_test[][];
    double Psi_test[][];
    double sigY_test[];

    // Adversarial
    private double[][] permutation;
    private int n_shp;
    public double[][] adv_train;

    // temporary variables useful for the derivatives of the shapelets
    double [][] tmp2;
    double regWConst, tmp1, tmp3, dLdY, gradW_crk, gradS_rkl, gradBiasW_c, eps = 0.000000000000000000001;

    Random rand = new Random();

    // store the indices of the positive and negative instances per each class
    List< List<Integer>> posIdxs;
    List< List<Integer>>  negIdxs;

    List<Integer> instanceIdxs;

    public boolean enableParallel=true;

//Parameter search settings
    boolean paraSearch=false; // AB: Cross Validation (CV)
    boolean pAdvSearch=false; // AB: Cross Validation (CV)

    double[] lambdaWRange = {0.01, 0.1};
    double[] percentageOfSeriesLengthRange = {0.15};
    int[] shapeletLengthScaleRange = {2, 4};
    double[] epsAdvRange = {0.01, 0.001, 0.1};

    @Override
    public void setParamSearch(boolean b) {
        paraSearch=b;

        //default the values to something.
//AARON has broken this
//        if(paraSearch)
//           fixParameters();
    }

//Set to defaults recommended by the author
    public void fixParameters(){
   // the regularization parameters
        lambdaW=0.1;
    // scales of the shapelet length
        R=4;
        percentageOfSeriesLength=0.15;
    // the learning rate
        eta=0.1;
    // the softmax parameter
        alpha=-30;
    // the number of iterations
        maxIter=300;
    // the adversarial perturbation
        eps_adv = 0.01;
    }

/* The actual parameter values should be set internally. This integer
  is just a key to maintain different parameter sets
    */
    @Override
    public void setParametersFromIndex(int x){
//Map integer: filthy hack,could be done better. Range is 1-8
        if(x<=4)
            lambdaW=lambdaWRange[0];
        else
            lambdaW=lambdaWRange[1];
//        if(x==1 || x==2||x==5||x==6)
            percentageOfSeriesLength=percentageOfSeriesLengthRange[0];
//        else
//            percentageOfSeriesLength=percentageOfSeriesLengthRange[1];
        if(x%2==1)
            R=shapeletLengthScaleRange[0];
        else
            R=shapeletLengthScaleRange[1];
    }

    @Override
    public String getParas(){
        return lambdaW+","+percentageOfSeriesLength+","+R;
    }

    @Override
    public double getAcc(){
        return maxAcc;
    }
    double maxAcc;

    // constructor
    public AdversarialShapelets() {
    }

    public void setSeed(long seed){
        this.seed = seed;
        rand = new Random(seed);
    }

    // initialize the data structures
    public void initialize() throws Exception {

        // avoid K=0
        if (K == 0) {
            K = 1;
        }

        L_min = (int)(percentageOfSeriesLength * seriesLength);

        // set the labels to be binary 0 and 1, needed for the logistic loss
        createOneVsAllTargets();

        // initialize the shapelets (complete initialization during the clustering)
        Shapelets = new double[R][][];
        // initialize the number of shapelets (by their starting point) and the length of the shapelets
        numberOfSegments = new int[R];
        L = new int[R];
        // set the lengths of shapelets and the number of segments
        // at each scale r
        int totalSegments = 0;

        //for each scale we create a number of segments and a shapelet length based on the scale value and our minimum shapelet length.
        for (int r = 0; r < R; r++) {
            L[r] = (r + 1) * L_min;
            numberOfSegments[r] = seriesLength - L[r];

            totalSegments += train.length * numberOfSegments[r];
        }

        // set the total number of shapelets per scale as a rule of thumb
        // to the logarithm of the total segments
        K = (int)(Math.log(totalSegments)*(C-1));

        // initialize the terms for pre-computation
        D_train = new double[train.length][R][K][];
        E_train = new double[train.length][R][K][];

        for (int i = 0; i < train.length; i++) {
            for (int r = 0; r < R; r++) {
                for (int k = 0; k < K; k++) {
                    D_train[i][r][k] = new double[numberOfSegments[r]];
                    E_train[i][r][k] = new double[numberOfSegments[r]];
                }
            }
        }

        // initialize the placeholders for the precomputed values
        M_train = new double[train.length][R][K];
        Psi_train = new double[train.length][R][K];
        sigY_train = new double[train.length][C];

        // initialize the weights
        W = new double[C][R][K];
        biasW = new double[C];

        for (int c = 0; c < C; c++) {
            for (int r = 0; r < R; r++) {
                for (int k = 0; k < K; k++) {
                    W[c][r][k] = 2*eps*rand.nextDouble() - 1;
                }
            }

            biasW[c] = 2*eps*rand.nextDouble() - 1;
        }

        // initialize gradient accumulators

        GradHistW = new double[C][R][K];
        GradHistBiasW = new double[C];

        GradHistShapelets = new double[R][][];
        for(int r=0; r<R; r++)
             GradHistShapelets[r] = new double[K][ L[r] ];


        initializeShapeletsKMeans();

        print("Initialization completed: L_min=" + L_min + ", K="+K
                +", R="+R + ", C="+C + ", lambdaW="+lambdaW);

        tmp2 = new double[R][];
        for(int r=0; r<R; r++)
            tmp2[r] = new double[numberOfSegments[r]];

        // initialize constant term for the regularization
        regWConst = ((double) 2.0 * lambdaW) / ((double) train.length);

        // initialize an array of the sizes
        instanceIdxs = new ArrayList<>();
        for (int i = 0; i < train.length; i++) {
            instanceIdxs.add(i);
        }

        // initialize adversarial elements
        permutation = new double[train.length][seriesLength];
        n_shp = 0;
        for (int r=0; r<R; r++) {
            n_shp += Shapelets[r].length;
        }
    }

    // create one-cs-all targets
    public void createOneVsAllTargets() {
        classValues_train = new double[train.length][C];

        // initialize the extended representation
        for (int i = 0; i < train.length; i++) {
            // firts set everything to zero
            for (int c = 0; c < C; c++) {
                classValues_train[i][c] =  0;
            }

            // then set the real label index to 1
            int indexLabel = nominalLabels.indexOf(trainSet.get(i).classValue());
            classValues_train[i][indexLabel] = 1.0;
        }

        // initialize the index lists
        posIdxs = new ArrayList<>();
        negIdxs = new ArrayList<>();
        // store the indices of the positive and negative instances per each class
        for (int c = 0; c < C; c++) {

            List<Integer> posIdx_c = new ArrayList<>();
            List<Integer> negIdx_c = new ArrayList<>();

            for (int i = 0; i < train.length; i++)
                if( classValues_train[i][c] == 1.0 )
                    posIdx_c.add(i);
                else
                    negIdx_c.add(i);

            posIdxs.add(posIdx_c);
            negIdxs.add(negIdx_c);
        }
    }

    // initialize the shapelets from the centroids of the segments
    public void initializeShapeletsKMeans() throws Exception {
        //for each scale r, i.e. for each set of K shapelets at
        int n_draw = 10000;
        int ind_ts, ind_time;
        Random rand = new Random();
        double[][] segments_r;
        // length L_min*(r+1)
        for (int r=0; r<R; r++) {
            int n_segments =train.length * numberOfSegments[r];

            if (n_segments <= n_draw) {
                segments_r = new double[n_segments][L[r]];

                //construct the segments from the train set.
                for (int i = 0; i < train.length; i++)
                    for (int j = 0; j < numberOfSegments[r]; j++)
                        for (int l = 0; l < L[r]; l++)
                            segments_r[i * numberOfSegments[r] + j][l] = train[i][j + l];

                // normalize segments
                for (int i = 0; i < train.length; i++)
                    for (int j = 0; j < numberOfSegments[r]; j++)
                        segments_r[i * numberOfSegments[r] + j] = StatisticalUtilities.normalize(segments_r[i * numberOfSegments[r] + j]);
            }
            else {
                segments_r = new double[n_draw][L[r]];

                //construct the segments from the train set.
                for (int ii = 0; ii < n_draw; ii++) {
                    ind_ts = rand.nextInt(train.length);
                    ind_time = rand.nextInt(numberOfSegments[r]);
                    for (int l = 0; l < L[r]; l++)
                        segments_r[ii][l] = train[ind_ts][ind_time + l];
                }

                // normalize segments
                for (int i = 0; i < n_draw; i++)
                    segments_r[i] = StatisticalUtilities.normalize(segments_r[i]);
            }

            Instances ins = InstanceTools.toWekaInstances(segments_r);

            SimpleKMeans skm = new SimpleKMeans();
            skm.setNumClusters(K);
            skm.setMaxIterations(100);
            //skm.setInitializeUsingKMeansPlusPlusMethod(true);
            skm.setSeed((int) (rand.nextDouble() * 1000) );
            skm.buildClusterer( ins );
            Instances centroidsWeka = skm.getClusterCentroids();
            Shapelets[r] =  InstanceTools.fromWekaInstancesArray(centroidsWeka, false);

            // initialize the gradient history of shapelets
            if (Shapelets[r] == null)
                print("P not set");
        }
    }

    // predict the label value vartheta_i
    public double predict_i(double[][] M, int c) {
        double Y_hat_ic = biasW[c];

        for (int r = 0; r < R; r++) {
            for (int k = 0; k < K; k++) {
                Y_hat_ic += M[r][k] * W[c][r][k];
            }
        }

        return Y_hat_ic;
    }

    // precompute terms
    public void preCompute(double[][][] D, double[][][] E, double[][] Psi, double[][] M, double[] sigY, double[] series) {

        // precompute terms
        for (int r = 0; r < R; r++) {
            //in most cases Shapelets[r].length == numLatentPatterns, this is not always true.
            for (int k = 0; k < Shapelets[r].length; k++) {
                for(int j = 0; j < numberOfSegments[r]; j++)
                {
                    // precompute D
                    D[r][k][j] = 0;
                    double err = 0;

                    for(int l = 0; l < L[r]; l++)
                    {
                        err = series[j + l] - Shapelets[r][k][l];
                        D[r][k][j] += err*err;
                    }

                    D[r][k][j] /= (double)L[r];

                    // precompute E
                    E[r][k][j] = Math.exp(alpha * D[r][k][j]);
                }

                // precompute Psi
                Psi[r][k] = 0;
                for(int j = 0; j < numberOfSegments[r]; j++)
                        Psi[r][k] +=  Math.exp( alpha * D[r][k][j] );

                // precompute M
                M[r][k] = 0;

                for(int j = 0; j < numberOfSegments[r]; j++)
                        M[r][k] += D[r][k][j]* E[r][k][j];

                M[r][k] /= Psi[r][k];
            }
        }

        for (int c = 0; c < C; c++) {
            sigY[c] = calculateSigmoid(predict_i(M, c));
        }
    }

    // compute the accuracy loss of instance i according to the
    // logistic loss
    public double accuracyLoss(double[][] M, double[] classValues, int c) {
        double Y_hat_ic = predict_i(M, c);
        double sig_y_ic = calculateSigmoid(Y_hat_ic);

        double returnVal = -classValues[c] * Math.log(sig_y_ic) - (1 - classValues[c]) * Math.log(1 - sig_y_ic);

        return returnVal;
    }

    // compute the accuracy loss of the train set
    public double accuracyLossTrainSet() {
        double accuracyLoss = 0;

        for (int i = 0; i < train.length; i++) {
            preCompute(D_train[i], E_train[i], Psi_train[i], M_train[i], sigY_train[i], train[i]);

            for (int c = 0; c < C; c++) {
                accuracyLoss += accuracyLoss(M_train[i], classValues_train[i], c);
            }
        }

        return accuracyLoss/train.length;
    }

    public void learnF(int c, int i) {
        preCompute(D_train[i], E_train[i], Psi_train[i], M_train[i], sigY_train[i], train[i]);

        dLdY = -(classValues_train[i][c] - sigY_train[i][c]);

        for (int r = 0; r < R; r++) {

            for (int k = 0; k < Shapelets[r].length; k++) {

                // update the weights
                gradW_crk=dLdY*M_train[i][r][k] + regWConst*W[c][r][k];
                GradHistW[c][r][k] += gradW_crk*gradW_crk;
                W[c][r][k] -= (eta / ( Math.sqrt(GradHistW[c][r][k]) + eps))*gradW_crk;

                // update the shapelets

                tmp1 = (2.0 / ((double) L[r] * Psi_train[i][r][k]));

                // precompute the term for speed up
                for (int j = 0; j < numberOfSegments[r]; j++)
                    tmp2[r][j] = E_train[i][r][k][j] * (1 + alpha * (D_train[i][r][k][j] - M_train[i][r][k]));

                for (int l = 0; l < L[r]; l++) {

                    tmp3 = 0;
                    for (int j = 0; j < numberOfSegments[r]; j++)
                        tmp3 += tmp2[r][j] * (Shapelets[r][k][l] - train[i][j + l]);

                    gradS_rkl =  dLdY * W[c][r][k] * tmp1 * tmp3;
                    GradHistShapelets[r][k][l] += gradS_rkl*gradS_rkl;
                    Shapelets[r][k][l] -= (eta / ( Math.sqrt(GradHistShapelets[r][k][l]) + eps))* gradS_rkl;
                }
            }
        }

        gradBiasW_c = dLdY;
        GradHistBiasW[c] += gradBiasW_c*gradBiasW_c;
        biasW[c] -= (eta / ( Math.sqrt(GradHistBiasW[c]) + eps))*gradBiasW_c;
    }

    public void learnF() {

        for (int c = 0; c < C; c++)
            for (double[] train1 : train) {
                if (posIdxs.get(c).size() > 0) {
                    // get a random index from the positive instances of this class
                    int posIdx = posIdxs.get(c).get( rand.nextInt(posIdxs.get(c).size()) );
                    // get a random index from the negative instances of this class
                    int negIdx = negIdxs.get(c).get( rand.nextInt(negIdxs.get(c).size()) );

                    // learn the model parameters acording to the objective
                    // of a random positive and negative class
                    learnF(c, posIdx);
                    learnF(c, negIdx);
                }
            }
    }

    // build a classifier using cross-validation to tune hyper-parameters
    @Override
    public void buildClassifier(Instances trainData) throws Exception {
        trainResults.buildTime=System.currentTimeMillis();

        nominalLabels = readNominalTargets(trainData);
        C = nominalLabels.size();

        if(paraSearch){
            double[] paramsLambdaW;
            double[] paramsPercentageOfSeriesLength;
            int[] paramsShapeletLengthScale;

            paramsLambdaW=lambdaWRange;
            paramsPercentageOfSeriesLength=percentageOfSeriesLengthRange;
            paramsShapeletLengthScale=shapeletLengthScaleRange;

            int noFolds = 2;
            double bsfAccuracy = 0;
            int[] params = {0,0,0};
            double accuracy;

            // randomize and stratify the data prior to cross validation
            trainData.randomize(rand);
            trainData.stratify(noFolds);

            int numHpsCombinations=1;

            for (int i = 0; i < paramsLambdaW.length; i++) {
                for (int j = 0; j < paramsPercentageOfSeriesLength.length; j++) {
                    for (int k = 0; k < paramsShapeletLengthScale.length; k++) {

                        percentageOfSeriesLength = paramsPercentageOfSeriesLength[j];
                        R = paramsShapeletLengthScale[k];
                        lambdaW = paramsLambdaW[i];

                        print("HPS Combination #"+numHpsCombinations+": {R="+R +
                                ", L="+percentageOfSeriesLength + ", lambdaW="+lambdaW + "}" );
                        //print("--------------------------------------");

                        double sumAccuracy = 0;
                        //build our test and train sets. for cross-validation.
                        for (int l = 0; l < noFolds; l++) {
                            Instances trainCV = trainData.trainCV(noFolds, l);
                            Instances testCV = trainData.testCV(noFolds, l);

                            // fixed hyper-parameters
                            eta = 0.1;
                            alpha = -30;
                            maxIter=300;
                            eps_adv = 0.01;

                            //print("Learn model for Fold-"+l + ":" );

                            train(trainCV);

                            //test on the remaining fold.
                            accuracy = utilities.ClassifierTools.accuracy(testCV, this);
                            sumAccuracy += accuracy;

                            //print("Accuracy-Fold-"+l + " = " + accuracy );

                        }
                        sumAccuracy/=noFolds;

                        print("Accuracy-CV = " + sumAccuracy );
                        //print("--------------------------------------");

                        if(sumAccuracy > bsfAccuracy){
                            int[] p = {i,j,k};
                            params = p;
                            bsfAccuracy = sumAccuracy;
                        }

                        numHpsCombinations++;
                    }
                }
            }

            System.gc();
            maxAcc=bsfAccuracy;
            lambdaW = paramsLambdaW[params[0]];
            percentageOfSeriesLength = paramsPercentageOfSeriesLength[params[1]];
            R = paramsShapeletLengthScale[params[2]];

            eta = 0.1;
            alpha = -30;
            maxIter = 600;
            eps_adv = 0.01;
            print("Learn final model with best hyper-parameters: R="+R
                                +", L="+percentageOfSeriesLength + ", lambdaW="+lambdaW);
        }
        else if (pAdvSearch) {
            fixParameters();
            double[] paramsEpsAdv;

            paramsEpsAdv=epsAdvRange;

            int noFolds = 2;
            double bsfAccuracy = 0;
            int params = 0;
            double accuracy;

            // randomize and stratify the data prior to cross validation
            trainData.randomize(rand);
            trainData.stratify(noFolds);

            int numHpsCombinations=1;

            for (int i = 0; i < paramsEpsAdv.length; i++) {
                eps_adv = paramsEpsAdv[i];

                print("HPS Combination #"+numHpsCombinations+": {R="+R +
                        ", L="+percentageOfSeriesLength + ", lambdaW="+lambdaW + ", eps_adv=" + eps_adv + "}" );
                //print("--------------------------------------");

                double sumAccuracy = 0;
                //build our test and train sets. for cross-validation.
                for (int l = 0; l < noFolds; l++) {
                    Instances trainCV = trainData.trainCV(noFolds, l);
                    Instances testCV = trainData.testCV(noFolds, l);

                    // fixed hyper-parameters
                    eta = 0.1;
                    alpha = -30;
                    maxIter=300;

                    //print("Learn model for Fold-"+l + ":" );

                    train(trainCV);

                    //test on the remaining fold.
                    accuracy = utilities.ClassifierTools.accuracy(testCV, this);
                    sumAccuracy += accuracy;

                    //print("Accuracy-Fold-"+l + " = " + accuracy );

                }
                sumAccuracy/=noFolds;

                print("Accuracy-CV = " + sumAccuracy );
                //print("--------------------------------------");

                if(sumAccuracy > bsfAccuracy){
                    params = i;
                    bsfAccuracy = sumAccuracy;
                }

                numHpsCombinations++;
            }

            System.gc();
            maxAcc=bsfAccuracy;
            eps_adv = paramsEpsAdv[params];

            eta = 0.1;
            alpha = -30;
            print("Learn final model with best hyper-parameters: R="+R
                                +", L="+percentageOfSeriesLength + ", lambdaW="+lambdaW + ", eps_adv=" + eps_adv + "}" );
        }
        else{
            fixParameters();
            print("Fixed parameters: R="+R
                                +", L="+percentageOfSeriesLength + ", lambdaW="+lambdaW + ", eps_adv=" + eps_adv + "}" );
        }

        maxIter = 2*maxIter;
        System.out.println("maxIter "+maxIter);
        nominalLabels = readNominalTargets(trainData);
        C = nominalLabels.size();

        trainSet = new Instances(trainData);
        trainSet.addAll(trainData); // Double for adversarial time series
        seriesLength = trainSet.numAttributes() - 1; //so we don't include the classLabel at the end.
        train = fromWekaInstancesArray(trainSet, true);
        
        // initialize the data structures
//        initialize();

        train(trainData);

        trainResults.buildTime=System.currentTimeMillis()-trainResults.buildTime;

    }

    private double gradient(int i, int c, int r, int j, double[] series) {
        int shapeletLength = Shapelets[r][0].length;
        double a = 2 / shapeletLength;
        double sumK = 0.0;
        double sumJ, dMdD, dDdx;

        for (int k=0; k<Shapelets[r].length; k++) {
            double a_temp = a / Psi_train[i][r][k];
            sumJ = 0.0;
            for (int jj=0; jj<numberOfSegments[r]; jj++) {
                if (jj <= j && j < jj+shapeletLength && j < series.length-shapeletLength) {
                    //System.out.println(j+" "+jj+" "+(j-jj)+" "+shapeletLength+" "+D_train[i][r][k].length+" "+series.length);
                    dMdD = E_train[i][r][k][j] * (1 + alpha * (D_train[i][r][k][j] - M_train[i][r][k])) * a_temp;
                    dDdx = a * (series[j] - Shapelets[r][k][j-jj]);
                    sumJ += (dMdD * dDdx);
                }
            }
            sumK += W[c][r][k] * sumJ;
        }

        return - classValues_train[i][c] * sumK;
    }

    private void generate_adversarial(Instances data) throws Exception {
        adv_train = fromWekaInstancesArray(data, true);

        C = data.numClasses();

        for (int i=0; i<adv_train.length; i++) {
            preCompute(D_train[i], E_train[i], Psi_train[i], M_train[i], sigY_train[i], adv_train[i]);

            for (int c=0; c<C; c++) {
                // do not compute Y_hat_ic since no impact on sign
                for(int r=0; r<R; r++) {
                    for (int j=0; j<seriesLength; j++) {
                        permutation[i][j] += gradient(i, c, r, j, adv_train[i]);
                    }
                }
            }
        }

        for (int i=0; i<adv_train.length; i++) {
            for (int j=0; j<seriesLength; j++) {
                train[adv_train.length+i][j] = train[i][j] + eps_adv * Math.signum(permutation[i][j]);
                permutation[i][j] = 0;
            }
        }
    }

    private void train(Instances data) throws Exception {

        trainSet = new Instances(data);
        trainSet.addAll(data); // Double for adversarial time series
        seriesLength = trainSet.numAttributes() - 1; //so we don't include the classLabel at the end.

        nominalLabels = readNominalTargets(trainSet);

        if(nominalLabels.size() < 2)
        {
            System.err.println("Fatal error: Number of classes is " + nominalLabels.size());
            return;
        }

        //convert the training set into a 2D Matrix.
        train = fromWekaInstancesArray(trainSet, true);

        // Z-normalize the training time seriee
//        for(int i=0; i<train.length; i++)
//            train[i] = StatisticalUtilities.normalize(train[i]);

        // initialize the data structures
        initialize();

        // apply the stochastic gradient descent in a series of iterations
        for (int iter = 0; iter <= maxIter; iter++) {
            generate_adversarial(data);
            // learn the latent matrices
            learnF();

            // measure the loss
            if ((iter %(maxIter/3)) == 0 && iter>0)
            {
                double lossTrain = accuracyLossTrainSet();

                print("Iter="+iter+", Loss="+lossTrain);

                // if divergence is detected break
                if ( Double.isNaN(lossTrain) )
                    break;
            }
        }
        /*
        System.out.print("Shapelets");
        for(int r=0; r<R; r++){
            System.out.print(" " + Shapelets[r].length);
        }
        System.out.println("");//*/
    }

    @Override
    public double classifyInstance(Instance instance) throws Exception {


        double[] temp = instance.toDoubleArray();
//remove the class value
        double[] test=new double[temp.length-1];
        System.arraycopy(temp, 0, test, 0, temp.length-1);

        // z-normalize time series
        test = StatisticalUtilities.normalize(test);

        // initialize the terms for pre-computation
        D_test = new double[R][K][];
        E_test = new double[R][K][];

        for (int r = 0; r < R; r++) {
            for (int k = 0; k < K; k++) {
                D_test[r][k] = new double[numberOfSegments[r]];
                E_test[r][k] = new double[numberOfSegments[r]];
            }
        }

        // initialize the placeholders for the precomputed values
        M_test = new double[R][K];
        Psi_test = new double[R][K];
        sigY_test = new double[C];

        preCompute(D_test, E_test, Psi_test, M_test, sigY_test, test);


        double max_Y_hat_ic = Double.MIN_VALUE;
        int label_i = 0;

        for (int c = 0; c < C; c++) {
            double Y_hat_ic = calculateSigmoid(predict_i(M_test, c));

            if (Y_hat_ic > max_Y_hat_ic) {
                max_Y_hat_ic = Y_hat_ic;
                label_i = c;
            }
        }

        return nominalLabels.get(label_i);
    }

    public void suppressOutput(){
        suppressOutput = true;
    }

    void print(String s){
        if(!suppressOutput)
            System.out.println(s);
    }

    @Override
    public Capabilities getCapabilities() {
        throw new UnsupportedOperationException("Not supported yet."); //To change body of generated methods, choose Tools | Templates.
    }

    public static ArrayList<Double> readNominalTargets(Instances instances) {
        if (instances.size() <= 0)  return null;

        ArrayList<Double> nominalLabels = new ArrayList<>();

        for (Instance ins : instances) {
            boolean alreadyAdded = false;

            for (Double nominalLabel : nominalLabels) {
                if (nominalLabel == ins.classValue()) {
                    alreadyAdded = true;
                    break;
                }
            }

            if (!alreadyAdded) {
                nominalLabels.add(ins.classValue());
            }
        }

        Collections.sort(nominalLabels);

        return nominalLabels;
    }

    @SuppressWarnings("empty-statement")
    public static void main(String[] args) throws Exception {
        String[] list_datasets = {"SonyAIBORobotSurface", "BeetleFly", "DiatomSizeReduction"};
//        for(int i = 0; i < args.length; i++) {
//            System.out.println(args[i]);
//        }
        if ( args.length > 0 ) {
                list_datasets = args;
            }

        /*"SonyAIBORobotSurfaceII", "ItalyPowerDemand", "MoteStrain",
        "SonyAIBORobotSurfaceII", "TwoLeadECG", "ECGFiveDays", "CBF",
        "Gun_Point", "Coffee", "ECG200", "ShapeletSim",
        "BeetleFly", "BirdChicken", "DiatomSizeReduction",
        "ToeSegmentation1", "ToeSegmentation2", "Wine", "ArrowHead",
        "DistalPhalanxOutlineAgeGroup", "FaceFour", "MiddlePhalanxOutlineAgeGroup",
        "DistalPhalanxOutlineCorrect", "MiddlePhalanxOutlineCorrect",
        "Symbols", "Herring", "DistalPhalanxTW", "OliveOil", "Beef",
        "MiddlePhalanxTW", "Lighting2", "Meat", "Ham",
        "ProximalPhalanxOutlineAgeGroup", "ProximalPhalanxOutlineCorrect", "ProximalPhalanxTW",//*/
        /*  "Plane", "synthetic_control", "Trace", "Car",
        "WormsTwoClass", "Earthquakes", "Lighting7",
        "Strawberry", "ChlorineConcentration", "yoga",
        "CinC_ECG_torso", "PhalangesOutlinesCorrect",
        "wafer", "Worms", "ECG5000", "Computers", "FacesUCR",
        "MedicalImages", "MALLAT", "Two_Patterns", "OSULeaf",
        "FISH", "InsectWingbeatSound", "FordB",
        "LargeKitchenAppliances", "RefrigerationDevices",
        "ScreenType", "SmallKitchenAppliances", "Haptics",
        "SwedishLeaf", "FaceAll", "InlineSkate", "FordA",
        "Cricket_X", "Cricket_Y", "Cricket_Z", "WordsSynonyms",
        "HandOutlines", "uWaveGestureLibrary_X",
        "uWaveGestureLibrary_Y", "uWaveGestureLibrary_Z",
        "Adiac", "StarLightCurves", "ElectricDevices",
        "50words", "UWaveGestureLibraryAll", "Phoneme",
        "ShapesAll", "NonInvasiveFatalECG_Thorax1", "NonInvasiveFatalECG_Thorax2"};//*/

        for (String s: list_datasets) {
            args = new String[]{"/home/a-bailly/src/data/TSCProblems", s};
            //args = new String[]{"../mnt/temp_dd/igrida-fs1/abailly/data/TSCProblems", s};
            System.out.println("\n\nABS: "+s);

            //resample 1 of the italypowerdemand dataset
            String dataset = args[1];
            String fileExtension = File.separator + dataset + File.separator + dataset;
            String samplePath = args[0] + fileExtension;

            //load the train and test.
            Instances testSet = utilities.ClassifierTools.loadData(samplePath + "_TEST");
            Instances trainSet = utilities.ClassifierTools.loadData(samplePath + "_TRAIN");

            AdversarialShapelets ls = new AdversarialShapelets();
            ls.setSeed(0);
            ls.buildClassifier(trainSet);

            double accuracy = utilities.ClassifierTools.accuracy(testSet, ls);
            System.out.println(dataset+", ABS (ER) = " + (1 - accuracy));
        }
    }
}
