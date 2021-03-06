package com.taobao.pamirs.schedule;

import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


/**
 * 任务调度器，在TBScheduleManager的管理下实现多线程数据处理
 * @author xuannan
 * @param <T>
 * 修改记录：
 * 	  为了简化处理逻辑，去处版本概率，增加可能重复的数据列表   by  扶苏 20110310
 */
public class TBScheduleProcessorNotSleep<T> implements IScheduleProcessor, Runnable {
	
	private static transient Log logger = LogFactory.getLog(TBScheduleProcessorNotSleep.class);
	
	List<Thread> threadList =  Collections.synchronizedList(new ArrayList<Thread>());
	/**
	 * 任务管理器
	 */
	protected TBScheduleManager scheduleManager;
	/**
	 * 任务类型
	 */
	ScheduleTaskType taskTypeInfo;
	
	
	/**
	 * 任务处理的接口类
	 */
	protected IScheduleTaskDeal<T> taskDealBean;
	
	/**
	 * 任务比较器
	 */
	Comparator<T> taskComparator;

    //静态配置参数
	StatisticsInfo statisticsInfo;


	protected List<T> taskList =Collections.synchronizedList(new ArrayList<T>());
	/**
	 * 正在处理中的任务队列
	 */
	protected List<Object> runningTaskList = Collections.synchronizedList(new ArrayList<Object>()); 
	/**
	 * 在重新取数据，可能会重复的数据。在重新取数据前，从runningTaskList拷贝得来
     * 取得并没有执行的任务就是可能重复执行数据，实际上就是某个时间点下的workingList
	 */
	protected List<T> maybeRepeatTaskList = Collections.synchronizedList(new ArrayList<T>());

	//线程取单个任务和取多个任务都是上锁的
	//取多个任务的时候，无法在循环中也会取得单个任务的锁，所以当取多个任务的时候，取单个任务可以穿插在其中
	Lock lockFetchID = new ReentrantLock();
	Lock lockFetchMutilID = new ReentrantLock();
	Lock lockLoadData = new ReentrantLock();
	/**
	 * 是否可以批处理
	 */
	boolean isMutilTask = false;
	
	/**
	 * 是否已经获得终止调度信号
	 */
	boolean isStopSchedule = false;// 用户停止队列调度
	boolean isSleeping = false;
	
	/**
	 * 创建一个调度处理器
	 * @param aManager
	 * @param aTaskDealBean
	 * @param aStatisticsInfo
	 * @throws Exception
	 */
	public TBScheduleProcessorNotSleep(TBScheduleManager aManager,
			IScheduleTaskDeal<T> aTaskDealBean,StatisticsInfo aStatisticsInfo) throws Exception {
		this.scheduleManager = aManager;
		this.statisticsInfo = aStatisticsInfo;
		this.taskTypeInfo = this.scheduleManager.getTaskTypeInfo();
		this.taskDealBean = aTaskDealBean;
		this.taskComparator = new MYComparator(this.taskDealBean.getComparator());
		if (this.taskDealBean instanceof IScheduleTaskDealSingle<?>) {
			if (taskTypeInfo.getExecuteNumber() > 1) {
				taskTypeInfo.setExecuteNumber(1);
			}
			isMutilTask = false;
		} else {
			isMutilTask = true;
		}
		if (taskTypeInfo.getFetchDataNumber() < taskTypeInfo.getThreadNumber() * 10) {
			logger.warn("参数设置不合理，系统性能不佳。【每次从数据库获取的数量fetchnum】 >= 【线程数量threadnum】 *【最少循环次数10】 ");
		}
		for (int i = 0; i < taskTypeInfo.getThreadNumber(); i++) {
			this.startThread(i);
		}
	}

	/**
	 * 需要注意的是，调度服务器从配置中心注销的工作，必须在所有线程退出的情况下才能做
	 * @throws Exception
	 */
	public void stopSchedule() throws Exception {
		// 设置停止调度的标志,调度线程发现这个标志，执行完当前任务后，就退出调度
		this.isStopSchedule = true;
		//清除所有未处理任务,但已经进入处理队列的，需要处理完毕
		this.taskList.clear();
	}

	private void startThread(int index) {
		Thread thread = new Thread(this);
		threadList.add(thread);
		String threadName = this.scheduleManager.getScheduleServer().getTaskType()+"-"
				+ this.scheduleManager.getCurrentSerialNumber() + "-exe"
				+ index;
		thread.setName(threadName);
		thread.start();
	}
	
	@SuppressWarnings("unchecked")
	//任务是否正在被处理
	protected boolean isDealing(T aTask) {
		if (this.maybeRepeatTaskList.size() == 0) {
			return false;
		}
		T[] tmpList = (T[]) this.maybeRepeatTaskList.toArray();
		for (int i = 0; i < tmpList.length; i++) {
            //todo 如何对比任务是否可能被重复执行
            //简单的判断该任务是否已经被其他线程加入到maybeRepeatTaskList中
			if(this.taskComparator.compare(aTask, tmpList[i]) == 0){
				this.maybeRepeatTaskList.remove(tmpList[i]);
				return true;
			}
		}
		return false;
	}

	/**
	 * 获取单个任务，注意lock是必须，
	 * 否则在maybeRepeatTaskList的数据处理上会出现冲突
	 * @return
	 */
	public T getScheduleTaskId() {
		lockFetchID.lock();
		try {
			T result = null;
            /**
             * 从task队列头开始取得不会被重复执行的任务
             */
			while (true) {
				if (this.taskList.size() > 0) {
					result = this.taskList.remove(0); // 按正序处理
				} else {
					return null;
				}
				//多余的判断，因为从队列中拿任务是被lockFetchId锁保护的，所以只有一个thread对list进行操作。
				//也只有一个thread可以将拿到的任务放入runningTask队列中
				//如果取不到任务，某个线程将一直占用锁，其他线程全都在等待
				if (this.isDealing(result) == false) {
					return result;
				}
			}
		} finally {
			lockFetchID.unlock();
		}
	}
	/**
	 * 获取多个任务，注意lock是必须，
	 * 否则在maybeRepeatTaskList的数据处理上会出现冲突
	 * @return
	 */
	@SuppressWarnings("unchecked")
    /**
     * 取得多个任务不会取到可能重复的任务吗？？
     * 循环调用取单个任务，也可以避免取到重复的任务
     */
    //todo
	public T[] getScheduleTaskIdMulti() {
		lockFetchMutilID.lock();
		try {
			if (this.taskList.size() == 0) {
				return null;
			}
			int size = taskList.size() > taskTypeInfo.getExecuteNumber() ? taskTypeInfo
					.getExecuteNumber() : taskList.size();

			List<T> result = new ArrayList<T>();
			int point = 0;
			T tmpObject = null;
			while (point < size && ((tmpObject = this.getScheduleTaskId()) != null)) {
				result.add(tmpObject);
				point = point + 1;
			}
			if (result.size() == 0) {
				return null;
			} else {
				return (T[]) result.toArray();
			}
		} finally {
			lockFetchMutilID.unlock();
		}
	}
	
	public void clearAllHasFetchData(){
        this.taskList.clear();
	}
    public boolean isDealFinishAllData(){
    	return this.taskList.size() == 0 && this.runningTaskList.size() ==0;  
    }
    
    public boolean isSleeping(){
    	return this.isSleeping;
    }
    /**
     * 装载数据
     * @return
     */
	//当装载数据被调用时，说明Manager的任务队列已空，将isSleep标志位置为true
	protected int loadScheduleData() {
		//使用锁，保证只有一个线程可以命令manager加载任务
		lockLoadData.lock();
		try {
			if (this.taskList.size() > 0 || this.isStopSchedule == true) { // 判断是否有别的线程已经装载过了。
				return this.taskList.size();
			}
			// 在取数据的之前先睡眠
			try {
				if (this.taskTypeInfo.getSleepTimeInterval() > 0) {
					if (logger.isTraceEnabled()) {
						logger.trace("处理完一批数据后休眠："
								+ this.taskTypeInfo.getSleepTimeInterval());
					}
					//此方法被lockLaodData方法锁定，只有一个线程可以修改sleep标志位
					this.isSleeping = true;
					Thread.sleep(taskTypeInfo.getSleepTimeInterval());
					this.isSleeping = false;
					
					if (logger.isTraceEnabled()) {
						logger.trace("处理完一批数据后休眠后恢复");
					}
				}
			} catch (Throwable ex) {
				logger.error("休眠时错误", ex);
			}

			putLastRunningTaskList();// 将running队列的数据拷贝到可能重复的队列中
			//某些任务可能正在被其他线程操作，虽然队列池已经空了，不代表所有任务都已经完成
			try {
				List<TaskItemDefine> taskItems = this.scheduleManager
						.getCurrentScheduleTaskItemList();
				// 根据队列信息查询需要调度的数据，然后增加到任务列表中
				if (taskItems.size() > 0) {
					List<T> tmpList = this.taskDealBean.selectTasks(
							taskTypeInfo.getTaskParameter(),
							scheduleManager.getScheduleServer()
									.getOwnSign(), this.scheduleManager.getTaskItemCount(), taskItems,
							taskTypeInfo.getFetchDataNumber());
					scheduleManager.getScheduleServer().setLastFetchDataTime(new Timestamp(ScheduleUtil.getCurrentTimeMillis()));
					if (tmpList != null) {
						this.taskList.addAll(tmpList);
					}
				} else {
					if (logger.isDebugEnabled()) {
						logger.debug("没有任务分配");
					}
				}
				addFetchNum(taskList.size(),
						"TBScheduleProcessor.loadScheduleData");
				if (taskList.size() <= 0) {
					// 判断当没有数据的是否，是否需要退出调度
					if (this.scheduleManager.isContinueWhenData() == true) {
						if (taskTypeInfo.getSleepTimeNoData() > 0) {
							if (logger.isDebugEnabled()) {
								logger.debug("没有读取到需要处理的数据,sleep "
										+ taskTypeInfo.getSleepTimeNoData());
							}
							//没取到任务再睡一会儿
							this.isSleeping = true;
							Thread.sleep(taskTypeInfo.getSleepTimeNoData());
							this.isSleeping = false;							
						}
					}
				}
				return this.taskList.size();
			} catch (Throwable ex) {
				logger.error("获取任务数据错误", ex);
			}
			return 0;
		} finally {
			lockLoadData.unlock();
		}
	}
	/**
	 * 将running队列的数据拷贝到可能重复的队列中
	 */
	@SuppressWarnings("unchecked")
	public void putLastRunningTaskList() {
		lockFetchID.lock();
		try {
			this.maybeRepeatTaskList.clear();
			if (this.runningTaskList.size() == 0) {
				return;
			}
			Object[] tmpList = this.runningTaskList.toArray();
			for (int i = 0; i < tmpList.length; i++) {
                //不可以批处理
				if (this.isMutilTask == false) {
					this.maybeRepeatTaskList.add((T) tmpList[i]);
				} else {
					T[] aTasks = (T[]) tmpList[i];
					for (int j = 0; j < aTasks.length; j++) {
						this.maybeRepeatTaskList.add(aTasks[j]);
					}
				}
			}
		} finally {
			lockFetchID.unlock();
		}
	}
	
	/**
	 * 运行函数
	 */
	@SuppressWarnings("unchecked")
	public void run() {
		long startTime = 0;
		long sequence = 0;
		Object executeTask = null;	
		while (true) {
			try {
				if (this.isStopSchedule == true) { // 停止队列调度
					this.threadList.remove(Thread.currentThread());
					if(this.threadList.size()==0){
						this.scheduleManager.unRegisterScheduleServer();
					}
					return;
				}
				// 加载调度任务
				if (this.isMutilTask == false) {
					//取任务操作线程安全，通过两个锁，保证同时只有一个线程在取任务
					executeTask = this.getScheduleTaskId();
				} else {
					executeTask = this.getScheduleTaskIdMulti();
				}
				if (executeTask == null ) {
					//manager中已经没有任务时，主动去dataManager取得自己的任务
					//保证只有一个线程在取任务，由loadDataLock保护
					this.loadScheduleData();
					continue;
				}
				
				try { // 运行相关的程序
					//正在处理的队列，队列是线程安全的
					this.runningTaskList.add(executeTask);
					startTime = ScheduleUtil.getCurrentTimeMillis();
					sequence = sequence + 1;
					if (this.isMutilTask == false) {
						if (((IScheduleTaskDealSingle<Object>) this.taskDealBean).execute(executeTask,scheduleManager.getScheduleServer().getOwnSign()) == true) {
							addSuccessNum(1, ScheduleUtil.getCurrentTimeMillis()
									- startTime,
									"com.taobao.pamirs.schedule.TBScheduleProcessorNotSleep.run");
						} else {
							addFailNum(1, ScheduleUtil.getCurrentTimeMillis()
									- startTime,
									"com.taobao.pamirs.schedule.TBScheduleProcessorNotSleep.run");
						}
					} else {
						if (((IScheduleTaskDealMulti<Object>) this.taskDealBean)
								.execute((Object[]) executeTask,scheduleManager.getScheduleServer().getOwnSign()) == true) {
							addSuccessNum(((Object[]) executeTask).length, ScheduleUtil
									.getCurrentTimeMillis()
									- startTime,
									"com.taobao.pamirs.schedule.TBScheduleProcessorNotSleep.run");
						} else {
							addFailNum(((Object[]) executeTask).length, ScheduleUtil
									.getCurrentTimeMillis()
									- startTime,
									"com.taobao.pamirs.schedule.TBScheduleProcessorNotSleep.run");
						}
					}
				} catch (Throwable ex) {
					if (this.isMutilTask == false) {
						addFailNum(1, ScheduleUtil.getCurrentTimeMillis() - startTime,
								"TBScheduleProcessor.run");
					} else {
						addFailNum(((Object[]) executeTask).length, ScheduleUtil
								.getCurrentTimeMillis()
								- startTime,
								"TBScheduleProcessor.run");
					}
					logger.error("Task :" + executeTask + " 处理失败", ex);
				} finally {
					this.runningTaskList.remove(executeTask);
				}
			} catch (Throwable e) {
				throw new RuntimeException(e);
				//log.error(e.getMessage(), e);
			}
		}
	}

	public void addFetchNum(long num, String addr) {
			this.statisticsInfo.addFetchDataCount(1);
			this.statisticsInfo.addFetchDataNum(num);
	}

	public void addSuccessNum(long num, long spendTime, String addr) {
			this.statisticsInfo.addDealDataSucess(num);
			this.statisticsInfo.addDealSpendTime(spendTime);
	}

	public void addFailNum(long num, long spendTime, String addr) {
			this.statisticsInfo.addDealDataFail(num);
			this.statisticsInfo.addDealSpendTime(spendTime);
	}
	
    class MYComparator implements Comparator<T>{
    	Comparator<T> comparator;
    	public MYComparator(Comparator<T> aComparator){
    		this.comparator = aComparator;
    	}

		public int compare(T o1, T o2) {
			statisticsInfo.addOtherCompareCount(1);
			return this.comparator.compare(o1, o2);
		}
    	public  boolean equals(Object obj){
    	 return this.comparator.equals(obj);
    	}
    }
    
}
