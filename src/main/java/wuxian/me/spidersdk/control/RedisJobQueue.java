package wuxian.me.spidersdk.control;

import com.google.common.primitives.Ints;
import com.google.gson.Gson;
import com.sun.istack.internal.Nullable;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisConnectionException;
import wuxian.me.spidercommon.log.LogManager;
import wuxian.me.spidercommon.model.HttpUrlNode;
import wuxian.me.spidercommon.util.FileUtil;
import wuxian.me.spidercommon.util.ShellUtil;
import wuxian.me.spidersdk.BaseSpider;
import wuxian.me.spidersdk.JobManagerConfig;
import wuxian.me.spidersdk.distribute.*;
import wuxian.me.spidersdk.job.IJob;
import wuxian.me.spidersdk.job.JobProvider;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Created by wuxian on 10/5/2017.
 * <p>
 * 使用url存入RedisJobQueue的方式,pull的时候加一层解析的方式来实现分布式JobQueue
 */
public class RedisJobQueue implements IQueue {

    private static final String JOB_QUEUE = "jobqueue";

    private Map<Long, Class> urlPatternMap = new HashMap<Long, Class>();
    private List<Long> unResolveList = new ArrayList<Long>();

    private Jedis jedis;
    private Gson gson;
    private boolean started = false;

    public RedisJobQueue() {

    }

    public void init() {
        if (started) {
            return;
        }
        started = true;

        gson = new Gson();
        String path = FileUtil.getCurrentPath() + "/util/shell/checkredisrunning";
        if (!FileUtil.checkFileExist(path)) {
            String shell = "redis-cli -h " + JobManagerConfig.redisIp + " -p "
                    + JobManagerConfig.redisPort + " ping";
            FileUtil.writeToFile(path, shell);
        }
        ShellUtil.chmod(path, 0777);


        boolean redisRunning = false;
        try {
            redisRunning = ShellUtil.isRedisServerRunning();
        } catch (IOException e) {
            ;
        }

        if (redisRunning) {
            LogManager.info("redis server is running ");
        } else {
            LogManager.info("redis server is not running");
        }


        if (!redisRunning) {
            throw new JobManagerInitErrorException("Redis server not running");
        }

        LogManager.info("init jedis client");
        jedis = new Jedis(JobManagerConfig.redisIp,
                Ints.checkedCast(JobManagerConfig.redisPort));

        try {
            jedis.exists(JOB_QUEUE);
        } catch (JedisConnectionException e) {
            LogManager.error("JedisConnectionException e:" + e.getMessage());
        }

        LogManager.info("redis jobqueue init END");

    }


    //抛弃state --> 分布式下没法管理一个job的状态:是新开始的任务还是重试的任务
    public synchronized boolean putJob(IJob job, int state) {

        if (!JobManagerConfig.enablePutSpiderToQueue) {
            return false;
        }

        BaseSpider spider = (BaseSpider) job.getRealRunnable();
        HttpUrlNode urlNode = spider.toUrlNode();

        if (urlNode == null) {
            return false;
        }
        LogManager.info("try to Put Spider");
        if (state == IJob.STATE_INIT) {
            String key = String.valueOf(urlNode.toRedisKey());
            if (jedis.exists(key) && !JobManagerConfig.enableInsertDuplicateJob) {
                LogManager.info("Spider is dulpilicate,so abandon");
                return false;  //重复任务 抛弃
            }
            jedis.set(key, "true");
        }
        String json = gson.toJson(urlNode);
        jedis.lpush(JOB_QUEUE, json);  //会存在一点并发的问题 但认为可以接受
        LogManager.info("Success Put Spider: " + spider.name());
        LogManager.info("Current redis job num is " + getJobNum());
        return true;
    }

    //use in @DistributeJobManager.onResume: putting back job to redisjobqueue,
    //so we will ignore if jedis.exists(key)
    public boolean putJob(HttpUrlNode urlNode) {
        if (urlNode == null) {
            return false;
        }

        jedis.lpush(JOB_QUEUE, gson.toJson(urlNode));
        return true;
    }


    int MAX_UNRESOLVE_NUM = 30;


    private IJob getJob(int tryTime) {

        if (!JobManagerConfig.enableGetSpiderFromQueue) {
            return null;
        }

        String spiderStr = jedis.rpop(JOB_QUEUE);
        if (spiderStr == null) {
            LogManager.info("try get spider but queue is empty,return null");
            return null;
        }

        HttpUrlNode node = null;
        try {
            node = gson.fromJson(spiderStr, HttpUrlNode.class);
        } catch (Exception e) {

            LogManager.info("getJob decodeJson error,get another one");
            return getJob(tryTime + 1);
        }

        long hash = node.toPatternKey();
        if (unResolveList.contains(hash)) {
            jedis.lpush(JOB_QUEUE, spiderStr); //无法解析的重新放入job queue

            if (tryTime >= MAX_UNRESOLVE_NUM) {  //Fix StackOverFlow
                LogManager.info("too many times can't get a node,return null");
                return null;
            }

            return getJob(tryTime + 1);
        }

        if (!urlPatternMap.containsKey(hash)) {
            Class clazz = getHandleableClassOf(node);
            if (clazz == null) {
                unResolveList.add(hash);

                jedis.lpush(JOB_QUEUE, spiderStr);
                if (tryTime >= MAX_UNRESOLVE_NUM) {
                    LogManager.info("too many times can't get a node,return null");
                    return null;
                }

                return getJob(tryTime + 1);

            } else {
                urlPatternMap.put(hash, clazz);  //找到hash对应的spider class
            }
        }

        Method fromUrl = SpiderMethodManager.getFromUrlMethod(urlPatternMap.get(hash));
        if (fromUrl == null) {   //有可能为null
            unResolveList.add(hash);
            jedis.lpush(JOB_QUEUE, spiderStr);
            if (tryTime >= MAX_UNRESOLVE_NUM) {  //Fix StackOverFlow
                LogManager.info("too many times can't get a node,return null");
                return null;
            }
            return getJob(tryTime + 1);
        }

        try {
            BaseSpider spider = (BaseSpider) fromUrl.invoke(null, node);
            LogManager.info("Get Spider: " + spider.name());
            LogManager.info("Current redis job num is " + getJobNum());
            IJob job = JobProvider.getJob();
            job.setRealRunnable(spider);

            return job;
        } catch (IllegalAccessException e) {

            return getJob(tryTime + 1);

        } catch (InvocationTargetException e) {
            return getJob(tryTime + 1);

        }
    }

    //允许返回空值 须判空
    @Nullable
    public synchronized IJob getJob() {
        return getJob(1);
    }


    private Class getHandleableClassOf(HttpUrlNode node) {


        for (Class clazz : SpiderMethodManager.getSpiderClasses()) {
            Method fromUrl = SpiderMethodManager.getFromUrlMethod(clazz);
            if (fromUrl == null) {
                continue;
            }
            try {
                BaseSpider spider = (BaseSpider) fromUrl.invoke(null, node);
                if (spider != null) {
                    return clazz;
                } else {
                    continue;
                }
            } catch (IllegalAccessException e) {


            } catch (InvocationTargetException e) {


            }
        }

        return null;
    }

    public boolean isEmpty() {
        return getJobNum() == 0;
    }

    public int getJobNum() {
        return Ints.checkedCast(jedis.llen(JOB_QUEUE));
    }

}
