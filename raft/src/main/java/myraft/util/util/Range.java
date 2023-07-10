package myraft.util.util;

public class Range<T> {

    private final T left;
    private final T right;

    public Range(T left, T right) {
        this.left = left;
        this.right = right;
    }

    public T getLeft() {
        return left;
    }

    public T getRight() {
        return right;
    }
}
