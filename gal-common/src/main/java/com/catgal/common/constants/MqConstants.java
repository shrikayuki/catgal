package com.catgal.common.constants;

public interface MqConstants {
    interface Exchange{


        /*异常信息的交换机*/
        String ERROR_EXCHANGE = "error.topic";
        /*点赞数交换机*/
        String LIKE_COUNT_EXCHANGE = "like.count.topic";
        /*收藏业务相关交换机*/
        String FAVORITE_EXCHANGE = "favorite.exchange";
        /*萌萌点业务相关交换机*/
        String CUTE_POINT_EXCHANGE = "cute.point.topic";
        /*抽奖记录业务交换机*/
        String DRAW_RECORD_EXCHANGE = "draw.record.exchange";


    }
    interface Queue {
        String ERROR_QUEUE_TEMPLATE = "error.{}.queue";
        /*点赞相关队列*/
        String LIKE_COUNT_QUEUE_TEMPLATE = "like.{}.queue";

        String FAVORITE_SYNC_QUEUE = "favorite.sync.queue";

        /*萌萌点相关队列*/
        String CUTE_POINTS_QUEUE = "cute.points.{}.queue";
        /*抽奖记录业务队列*/
        String DRAW_RECORD_QUEUE = "draw.record.queue";
    }
    interface Key{


        /*异常RoutingKey的前缀*/
        String ERROR_KEY_PREFIX = "error.";
        String DEFAULT_ERROR_KEY = "error.#";
        String BIZ_TYPE_LIKED_TIMES_KEY = "{}.liked.times";
        String COMMENT_LIKED_TIMES_KEY = "comment.liked.times";
        String REVIEW_LIKED_TIMES_KEY = "review.liked.times";
        String RESOURCE_LIKED_TIMES_KEY = "resource.liked.times";

        String FAVORITE_CHANGE_KEY = "favorite.change";

        String BIZ_TYPE_CUTE_POINTS_KEY = "{}.cute.points";
        String SIGN_CUTE_POINTS_KEY = "sign_in.cute.points";
        String DRAW_CONSUME_CUTE_POINTS_KEY = "draw_consumed.cute.points";
        String DRAW_RECEIVE_CUTE_POINTS_KEY = "draw_received.cute.points";

        String DRAW_RECORD_KEY = "draw.record";

    }
}
