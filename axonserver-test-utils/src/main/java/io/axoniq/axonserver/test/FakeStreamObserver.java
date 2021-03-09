package io.axoniq.axonserver.test;

import io.grpc.stub.CallStreamObserver;
import io.grpc.stub.StreamObserver;

import java.util.LinkedList;
import java.util.List;

/**
 * Fake implementation of {@link StreamObserver} useful for testing.
 *
 * @author Sara Pellegrini
 */
public class FakeStreamObserver<M> extends CallStreamObserver<M> {

    private List<M> values = new LinkedList<>();
    private List<Throwable> errors = new LinkedList<>();
    private int completedCount = 0;

    @Override
    public void onNext(M value) {
        values.add(value);
    }

    @Override
    public void onError(Throwable t) {
        errors.add(t);
    }

    @Override
    public void onCompleted() {
        completedCount++;
    }

    public List<M> values() {
        return values;
    }

    public List<Throwable> errors() {
        return errors;
    }

    public int completedCount() {
        return completedCount;
    }

    @Override
    public boolean isReady() {
        return false;
    }

    @Override
    public void setOnReadyHandler(Runnable runnable) {

    }

    @Override
    public void disableAutoInboundFlowControl() {

    }

    @Override
    public void request(int i) {

    }

    @Override
    public void setMessageCompression(boolean b) {

    }
}
