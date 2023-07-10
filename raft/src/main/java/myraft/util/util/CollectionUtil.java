package myraft.util.util;

import java.util.Collection;

public class CollectionUtil {

    public static <T> boolean isEmpty(Collection<T> collection){
        return collection == null || collection.isEmpty();
    }
}
