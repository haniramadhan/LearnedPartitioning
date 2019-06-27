package org.apache.spark.sql.execution.dita.index.learned;

import org.apache.spark.sql.catalyst.expressions.dita.common.shape.Point;
import org.apache.spark.sql.catalyst.expressions.dita.common.trajectory.Trajectory;
import org.tensorflow.SavedModelBundle;
import org.tensorflow.Tensor;

import java.io.*;
import java.util.ArrayList;

public class LearnedModel {

    private static SavedModelBundle[] models;
    private static long checkpointTime = 1561471647;
    private static float maxThreshold, dissimilarityThreshold;
    private static ArrayList<ArrayList<Integer>> clusters;
    private static int numClusters;
    private static LearnedModel ourInstance = new LearnedModel();

    public static LearnedModel getInstance() {
        return ourInstance;
    }

    public void init(String modelsPath, int numModels, String parameterPath){
        loadModel(modelsPath,numModels);
        loadParameters(parameterPath);
    }

    private static void loadModel(String path, int numModels) {
        models = new SavedModelBundle[6];
        for (int i = 0; i < numModels; i++) {
            File modelDir = new File(path + i);
            for (File f : modelDir.listFiles()) {
                if (Long.parseLong(f.getName()) < checkpointTime)
                    continue;
                models[i] = SavedModelBundle.load(path + i + "/" + f.getName(), "serve");
            }
        }
    }

    private static void loadParameters(String filePath) {
        numClusters = -999;
        clusters = new ArrayList<>();
        File parameters = new File(filePath);
        try (BufferedReader bufferedReader = new BufferedReader(new FileReader(parameters))) {
            String line = bufferedReader.readLine();
            String[] tokens = line.split(",");
            maxThreshold = Float.parseFloat(tokens[0]);
            dissimilarityThreshold = Float.parseFloat(tokens[1]);
            for (int i = 2; i < tokens.length; i++) {
                int c = Integer.parseInt(tokens[i].trim());
                if (numClusters < c + 1)
                    numClusters = c + 1;
            }

            for(int i=0;i<numClusters;i++){
                clusters.add(new ArrayList<>());
            }
            for (int i = 2; i < tokens.length; i++) {
                int c = Integer.parseInt(tokens[i].trim());
                clusters.get(c).add(i-2);
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public ArrayList<Integer> predict(Trajectory t, double threshold) {
        double probThreshold = (maxThreshold - threshold) / maxThreshold * (1 - dissimilarityThreshold);

        Point[] points = getFirstLastPivotPoint(t);
        float[][] input = convertPointsToFloat(points);

        Tensor<Float> tInput = Tensor.create(input, Float.class);
        Tensor<Float> tPredict = models[0].session().runner().fetch("input2/Sigmoid")
                .feed("input1_input", tInput)
                .run().get(0).expect(Float.class);
        float[][] clusterLikelihood = new float[1][numClusters];
        clusterLikelihood = tPredict.copyTo(clusterLikelihood);
        ArrayList<Integer> hitLabels = new ArrayList<>();

        for (int i = 0; i < numClusters; i++) {
            if (clusterLikelihood[0][i] < probThreshold)
                continue;
            Tensor<Float> tPredictLv2 = models[i + 1].session().runner().fetch("input2/Sigmoid")
                    .feed("input1_input", tInput)
                    .run().get(0).expect(Float.class);
            float[][] clusterLv2Likelihood = new float[1][clusters.get(i).size()];
            clusterLv2Likelihood = tPredictLv2.copyTo(clusterLv2Likelihood);
            for(int x =0; x<clusters.get(i).size();x++){
                if(clusterLv2Likelihood[0][x] < probThreshold)
                    continue;
                hitLabels.add(clusters.get(i).get(x));
            }
        }
        return hitLabels;
    }

    private float[][] convertPointsToFloat(Point[] points){
        float[][] floatPoints = new float[1][points.length*2];
        for(int i=0;i<points.length;i++){
            floatPoints[0][i*2] = (float) points[i].coord()[0];
            floatPoints[0][i*2] = (float) points[i].coord()[1];
        }
        return  floatPoints;
    }

    private Point[] getFirstLastPivotPoint(Trajectory t){

        Point[] points = new Point[2+1];
        points[0] = t.points()[0];
        points[1] = t.points()[1];
        points[2] = t.calcPivot(1)[0]._1;
        return points;
    }

    private LearnedModel() {


    }
}
