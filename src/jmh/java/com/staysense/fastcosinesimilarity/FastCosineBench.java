package com.staysense.fastcosinesimilarity;

import org.apache.commons.math3.analysis.function.Power;
import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;
import org.apache.lucene.store.ByteArrayDataInput;
import org.eclipse.january.dataset.Dataset;
import org.eclipse.january.dataset.DatasetFactory;
import org.eclipse.january.dataset.LinearAlgebra;
import org.elasticsearch.index.mapper.BinaryFieldMapper;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;

import java.nio.ByteBuffer;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@Fork(2)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@BenchmarkMode({Mode.AverageTime})
@Measurement(time = 1, timeUnit = TimeUnit.SECONDS)
@Warmup(time = 1, iterations = 2)
public class FastCosineBench {

    @State(Scope.Benchmark)
    public static class BenchState {
        public static final int VEC_LENGTH = 512;
        public double[] queryVector = new double[VEC_LENGTH];
        public double[] docVector = new double[VEC_LENGTH];
        public double queryVectorNorm;

        public RealVector docRealVector;
        public RealVector queryRealVector;

        public ByteArrayDataInput byteArrayDataInput;
        public byte[] docVectorFieldBytes;

        public Dataset queryVectorDataset;
        public Dataset docVectorDataset;

        @Setup(Level.Trial)
        public void setUp() {
            Random random = new Random(0xDEADBEEF);
            for (int i = 0; i < VEC_LENGTH; i++) {
                queryVector[i] = randomDoubleBetween(random, 0d, 1d);
                docVector[i] = randomDoubleBetween(random, 0d, 1d);
            }
            queryVectorNorm = 0d;
            for (double i : queryVector) {
                queryVectorNorm += Math.pow(i, 2);
            }

            // apache commons math
            docRealVector = new ArrayRealVector(docVector);
            queryRealVector = new ArrayRealVector(docVector);

            // calculateScore
            ByteBuffer docVectorBytesBuffer = ByteBuffer.allocate(docVector.length * Double.BYTES);
            for (double item : docVector) {
                docVectorBytesBuffer.putDouble(item);
            }

            BinaryFieldMapper.CustomBinaryDocValuesField customBinaryDocValuesField = new BinaryFieldMapper.CustomBinaryDocValuesField(
                    "foo",
                    docVectorBytesBuffer.array()
            );
            docVectorFieldBytes = customBinaryDocValuesField.binaryValue().bytes;
            byteArrayDataInput = new ByteArrayDataInput(new byte[docVectorBytesBuffer.capacity()]);

            queryVectorDataset = DatasetFactory.createFromObject(queryVector);
            docVectorDataset = DatasetFactory.createFromObject(docVector);
        }

        static double randomDoubleBetween(Random random, double start, double end) {
            double result = random.nextDouble();
            return result * end + (1.0 - result) * start;
        }
    }

    @Benchmark
    public double naiveCosineSimilarity(BenchState s) {
        double docVectorNorm = 0d;
        double score = 0d;

        // calculate dot product of document vector and query vector
        for (int i = 0; i < s.queryVector.length; i++) {
            score += s.docVector[i] * s.queryVector[i];

            docVectorNorm += Math.pow(s.docVector[i], 2.0);
        }
        score = score / (Math.sqrt(docVectorNorm * s.queryVectorNorm));
        return score;
    }

    @Benchmark
    public double commonsMathDotProduct(BenchState s) {
        return s.docRealVector.dotProduct(s.queryRealVector);
    }

    @Benchmark
    public double commonsMathCosineSimilarity(BenchState s) {
        double score = s.docRealVector.dotProduct(s.queryRealVector);
        double docVectorNorm = 0d;
        for (double item : s.docRealVector.mapToSelf(new Power(2)).toArray()) {
            docVectorNorm += item;
        }
        score = score / Math.sqrt(docVectorNorm * s.queryVectorNorm);
        return score;
    }

    @Benchmark
    public double calculateScore(BenchState s) {
        s.byteArrayDataInput.reset(s.docVectorFieldBytes);

        return FastCosineLeafFactory.calculateScore(
                1,
                s.byteArrayDataInput,
                s.docVectorFieldBytes,
                s.queryVector,
                s.queryVectorNorm
        );
    }

    /**
     * doc_vector.dot(query_vector) / (np.linalg.norm(doc_vector) * np.linalg.norm(query_vector))
     */
    @Benchmark
    public double januaryCosineSimilarity(BenchState s) {
        return (double) LinearAlgebra.dotProduct(s.docVectorDataset, s.queryVectorDataset).sum() /
                LinearAlgebra.norm(s.docVectorDataset) * LinearAlgebra.norm(s.queryVectorDataset);
    }
}
