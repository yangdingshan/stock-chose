package com.yangdingshan.stockchose.util;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * @Author: yangdingshan
 * @Date: 2023/8/14 23:44
 * @Description:
 */
public class LambadaTools {

    /**
     * 利用BiConsumer实现foreach循环支持index
     *
     * @param biConsumer
     * @param <T>
     * @return
     */
    public static <T> Consumer<T> forEachWithIndex(BiConsumer<T, Integer> biConsumer) {
        /*这里说明一下，我们每次传入forEach都是一个重新实例化的Consumer对象，在lambada表达式中我们无法对int进行++操作,
        我们模拟AtomicInteger对象，写个getAndIncrement方法，不能直接使用AtomicInteger哦*/
        class IncrementInt{
            int i = 0;
            public int getAndIncrement(){
                return i++;
            }
        }
        IncrementInt incrementInt = new IncrementInt();
        return t -> biConsumer.accept(t, incrementInt.getAndIncrement());
    }
}
