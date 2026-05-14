package com.catgal.common.constants;

public interface MqConstants {
    interface Exchange{


        /*异常信息的交换机*/
        String ERROR_EXCHANGE = "error.topic";
        /*点赞数交换机*/
        String LIKE_COUNT_EXCHANGE = "like.count.topic";
        /*收藏业务相关交换机*/
        String FAVORITE_EXCHANGE = "favorite.exchange";


    }
    interface Queue {
        String ERROR_QUEUE_TEMPLATE = "error.{}.queue";
        /*点赞相关队列*/
        String LIKE_COUNT_QUEUE_TEMPLATE = "like.{}.queue";

        String FAVORITE_SYNC_QUEUE = "favorite.sync.queue";
    }
    interface Key{


        /*异常RoutingKey的前缀*/
        String ERROR_KEY_PREFIX = "error.";
        String DEFAULT_ERROR_KEY = "error.#";

        String COMMENT_LIKED_TIMES_KEY = "comment.liked.times";
        String REVIEW_LIKED_TIMES_KEY = "review.liked.times";
        String RESOURCE_LIKED_TIMES_KEY = "resource.liked.times";

        String FAVORITE_CHANGE_KEY = "favorite.change";


    }
}
