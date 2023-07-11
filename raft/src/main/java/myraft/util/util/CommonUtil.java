package myraft.util.util;

import myraft.exception.MyRaftException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class CommonUtil {

    private static final Logger logger = LoggerFactory.getLogger(CommonUtil.class);

    /**
     * 并发的获得future列表的结果
     * */
    public static <T> List<T> concurrentGetRpcFutureResult(
            String info, List<Future<T>> futureList, ExecutorService threadPool, long timeout, TimeUnit timeUnit){
        CountDownLatch countDownLatch = new CountDownLatch(futureList.size());

        List<T> resultList = new ArrayList<>(futureList.size());

        for(Future<T> futureItem : futureList){
            threadPool.execute(()->{
                try {
                    logger.debug(info + " concurrentGetRpcFutureResult start!");
                    T result = futureItem.get(timeout,timeUnit);
                    logger.debug(info + " concurrentGetRpcFutureResult end!");

                    resultList.add(result);
                } catch (Exception e) {
                    // rpc异常不考虑
                    logger.error( "{} getFutureResult error!",info,e);
                } finally {
                    countDownLatch.countDown();
                }
            });
        }

        try {
            countDownLatch.await();
        } catch (InterruptedException e) {
            throw new MyRaftException("getFutureResult error!",e);
        }

        return resultList;
    }
}
