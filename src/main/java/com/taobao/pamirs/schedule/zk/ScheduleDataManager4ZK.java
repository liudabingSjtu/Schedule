package com.taobao.pamirs.schedule.zk;

import java.lang.reflect.Type;
import java.sql.Timestamp;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.taobao.pamirs.schedule.IScheduleDataManager;
import com.taobao.pamirs.schedule.ScheduleServer;
import com.taobao.pamirs.schedule.ScheduleTaskItem;
import com.taobao.pamirs.schedule.ScheduleTaskType;
import com.taobao.pamirs.schedule.ScheduleTaskTypeRunningInfo;
import com.taobao.pamirs.schedule.ScheduleUtil;
import com.taobao.pamirs.schedule.TBScheduleManager;
import com.taobao.pamirs.schedule.TaskItemDefine;

public class ScheduleDataManager4ZK implements IScheduleDataManager {
	private static transient Log log = LogFactory.getLog(ScheduleDataManager4ZK.class);
	private Gson gson ;
	private ZKManager zkManager;
	private String PATH_BaseTaskType;
	private String PATH_TaskItem = "taskItem";
	private String PATH_Server = "server";
    public ScheduleDataManager4ZK(ZKManager aZkManager) throws Exception {
    	this.zkManager = aZkManager;
    	gson = new GsonBuilder().registerTypeAdapter(Timestamp.class,new TimestampTypeAdapter()).setDateFormat("yyyy-MM-dd HH:mm:ss").create();

		this.PATH_BaseTaskType = this.zkManager.getRootPath() +"/baseTaskType";
		
		if (this.getZooKeeper().exists(this.PATH_BaseTaskType, false) == null) {
			ZKTools.createPath(getZooKeeper(),this.PATH_BaseTaskType, CreateMode.PERSISTENT, this.zkManager.getAcl());
		}
	}	
	
	public ZooKeeper getZooKeeper() throws Exception {
		return this.zkManager.getZooKeeper();
	}

	public void createBaseTaskType(ScheduleTaskType baseTaskType) throws Exception {
		if(baseTaskType.getBaseTaskType().indexOf("$") > 0){
			throw new Exception("调度任务" + baseTaskType.getBaseTaskType() +"名称不能包括特殊字符 $");
		}
		String zkPath =	this.PATH_BaseTaskType + "/"+ baseTaskType.getBaseTaskType();
		String valueString = this.gson.toJson(baseTaskType);
		if ( this.getZooKeeper().exists(zkPath, false) == null) {
			this.getZooKeeper().create(zkPath, valueString.getBytes(), this.zkManager.getAcl(),CreateMode.PERSISTENT);
		} else {
			throw new Exception("调度任务" + baseTaskType.getBaseTaskType() + "已经存在,如果确认需要重建，请先调用deleteTaskType(String baseTaskType)删除");
		}
	}

	public void updateBaseTaskType(ScheduleTaskType baseTaskType)
			throws Exception {
		if(baseTaskType.getBaseTaskType().indexOf("$") > 0){
			throw new Exception("调度任务" + baseTaskType.getBaseTaskType() +"名称不能包括特殊字符 $");
		}
		String zkPath =	this.PATH_BaseTaskType + "/"+ baseTaskType.getBaseTaskType();
		String valueString = this.gson.toJson(baseTaskType);
		if ( this.getZooKeeper().exists(zkPath, false) == null) {
			this.getZooKeeper().create(zkPath, valueString.getBytes(), this.zkManager.getAcl(),CreateMode.PERSISTENT);
		}else{ 
			this.getZooKeeper().setData(zkPath, valueString.getBytes(), -1);
		}
		
	}


	public void initialRunningInfo4Dynamic(String baseTaskType, String ownSign)throws Exception {
		 String taskType = TBScheduleManager.getTaskTypeByBaseAndOwnSign(baseTaskType, ownSign);
		 //清除所有的老信息，只有leader能执行此操作
		 String zkPath = this.PATH_BaseTaskType+"/"+ baseTaskType +"/" + taskType;
		 if(this.getZooKeeper().exists(zkPath, false) == null){
			 this.getZooKeeper().create(zkPath,null, this.zkManager.getAcl(),CreateMode.PERSISTENT);
		 }
	}

	/**
	 * 第一次调用时，将目录下的内容全部干掉
	 * @param baseTaskType
	 * @param ownSign
	 * @param uuid
	 * @throws Exception
	 */
	public void initialRunningInfo4Static(String baseTaskType, String ownSign,String uuid)
			throws Exception {
		  
		 String taskType = TBScheduleManager.getTaskTypeByBaseAndOwnSign(baseTaskType, ownSign);
		 //清除所有的老信息，只有leader能执行此操作
		 String zkPath = this.PATH_BaseTaskType+"/"+ baseTaskType +"/" + taskType+"/" + this.PATH_TaskItem;
		 ZKTools.deleteTree(this.getZooKeeper(),zkPath);
		 //创建目录
		 this.getZooKeeper().create(zkPath,null, this.zkManager.getAcl(),CreateMode.PERSISTENT);
		 //创建静态任务
		 this.createScheduleTaskItem(baseTaskType, ownSign,this.loadTaskTypeBaseInfo(baseTaskType).getTaskItems());
		 //标记信息初始化成功
		 setInitialRunningInfoSucuss(baseTaskType,taskType,uuid);
	}
	
	public void setInitialRunningInfoSucuss(String baseTaskType, String taskType,String uuid) throws Exception{
		 String zkPath = this.PATH_BaseTaskType+"/"+ baseTaskType +"/" + taskType+"/" + this.PATH_TaskItem;
		 this.getZooKeeper().setData(zkPath, uuid.getBytes(),-1);
	}
    public boolean isInitialRunningInfoSucuss(String baseTaskType, String ownSign) throws Exception{
    	String taskType = TBScheduleManager.getTaskTypeByBaseAndOwnSign(baseTaskType, ownSign);
    	String leader = this.getLeader(this.loadScheduleServerNames(taskType));
    	String zkPath = this.PATH_BaseTaskType+"/"+ baseTaskType +"/" + taskType+"/"+ this.PATH_TaskItem;
		if(this.getZooKeeper().exists(zkPath, false) != null){
			byte[] curContent = this.getZooKeeper().getData(zkPath,false,null);
			if(curContent != null && new String(curContent).equals(leader)){
				return true;
			}
		}
		return false;
    }

    public long updateReloadTaskItemFlag(String taskType) throws Exception{
    	String baseTaskType = TBScheduleManager.splitBaseTaskTypeFromTaskType(taskType);
		String zkPath = this.PATH_BaseTaskType + "/" + baseTaskType 
		        + "/" + taskType + "/" + this.PATH_Server;
		Stat stat = this.getZooKeeper().setData(zkPath,"reload=true".getBytes(),-1);
		return stat.getVersion();

    }
    public long getReloadTaskItemFlag(String taskType) throws Exception{
    	String baseTaskType = TBScheduleManager.splitBaseTaskTypeFromTaskType(taskType);
		String zkPath = this.PATH_BaseTaskType + "/" + baseTaskType 
		        + "/" + taskType + "/" + this.PATH_Server;
		Stat stat = new Stat();
		this.getZooKeeper().getData(zkPath, false, stat);
    	return stat.getVersion();
    }
	/**
	 * 根据基础配置里面的任务项来创建各个域里面的任务项
	 * @param baseTaskType
	 * @param ownSign
	 * @param baseTaskItems
	 * @throws Exception
	 */
	public void createScheduleTaskItem(String baseTaskType, String ownSign,String[] baseTaskItems) throws Exception {
		ScheduleTaskItem[] taskItems = new ScheduleTaskItem[baseTaskItems.length];
		Pattern p = Pattern.compile("\\s*:\\s*\\{");
		
		for (int i=0;i<baseTaskItems.length;i++){
			taskItems[i] = new ScheduleTaskItem();
			taskItems[i].setBaseTaskType(baseTaskType);
			taskItems[i].setTaskType(TBScheduleManager.getTaskTypeByBaseAndOwnSign(baseTaskType, ownSign));
			taskItems[i].setOwnSign(ownSign);
			Matcher matcher = p.matcher(baseTaskItems[i]);
			if(matcher.find()){
				taskItems[i].setTaskItem(baseTaskItems[i].substring(0,matcher.start()).trim());
				taskItems[i].setDealParameter(baseTaskItems[i].substring(matcher.end(),baseTaskItems[i].length()-1).trim());
			}else{
				taskItems[i].setTaskItem(baseTaskItems[i]);
			}
			taskItems[i].setSts(ScheduleTaskItem.TaskItemSts.ACTIVTE);
		}
		createScheduleTaskItem(taskItems);
	}	
	/**
	 * 创建任务项，注意其中的 CurrentSever和RequestServer不会起作用
	 * @param taskItems
	 * @throws Exception
	 */
	public void createScheduleTaskItem(ScheduleTaskItem[] taskItems) throws Exception {
		for (ScheduleTaskItem taskItem : taskItems){
		   String zkPath = this.PATH_BaseTaskType + "/" + taskItem.getBaseTaskType() + "/" + taskItem.getTaskType() +"/" + this.PATH_TaskItem;
		   if(this.getZooKeeper().exists(zkPath, false)== null){
			   ZKTools.createPath(this.getZooKeeper(), zkPath, CreateMode.PERSISTENT, this.zkManager.getAcl());
		   }
		   String zkTaskItemPath = zkPath + "/" + taskItem.getTaskItem();
		   this.getZooKeeper().create(zkTaskItemPath,null, this.zkManager.getAcl(),CreateMode.PERSISTENT);
		   this.getZooKeeper().create(zkTaskItemPath + "/cur_server",null, this.zkManager.getAcl(),CreateMode.PERSISTENT);
		   this.getZooKeeper().create(zkTaskItemPath + "/req_server",null, this.zkManager.getAcl(),CreateMode.PERSISTENT);
		   this.getZooKeeper().create(zkTaskItemPath + "/sts",taskItem.getSts().toString().getBytes(), this.zkManager.getAcl(),CreateMode.PERSISTENT);
		   this.getZooKeeper().create(zkTaskItemPath + "/parameter",taskItem.getDealParameter().getBytes(), this.zkManager.getAcl(),CreateMode.PERSISTENT);
		   this.getZooKeeper().create(zkTaskItemPath + "/deal_desc",taskItem.getDealDesc().getBytes(), this.  .getAcl(),CreateMode.PERSISTENT);
		}
	}
	
	public void updateScheduleTaskItemStatus(String taskType,String taskItem,ScheduleTaskItem.TaskItemSts sts,String message) throws Exception{
		String baseTaskType = TBScheduleManager.splitBaseTaskTypeFromTaskType(taskType);
		String zkPath = this.PATH_BaseTaskType + "/" + baseTaskType + "/" + taskType +"/" + this.PATH_TaskItem +"/" + taskItem;
		if(this.getZooKeeper().exists(zkPath +"/sts", false)== null){
			this.getZooKeeper().setData(zkPath +"/sts", sts.toString().getBytes(), -1);
		}
		if(this.getZooKeeper().exists(zkPath +"/deal_desc", false)== null){
			if(message == null){
				message = "";
			}
			this.getZooKeeper().setData(zkPath +"/deal_desc",message.getBytes(), -1);
		}
	}

	/**
	 * 删除任务项
	 * @param taskType
	 * @param taskItem
	 * @throws Exception 
	 */
	public void deleteScheduleTaskItem(String taskType,String taskItem) throws Exception{
		String baseTaskType = TBScheduleManager.splitBaseTaskTypeFromTaskType(taskType);
		String zkPath = this.PATH_BaseTaskType + "/" + baseTaskType + "/" + taskType +"/" + this.PATH_TaskItem +"/" + taskItem;
		ZKTools.deleteTree(this.getZooKeeper(), zkPath);
	}
	//将zookeeper中的数据进行预处理，转换成ScheduleTaskItem队列

	/**
	 * 任务信息实际上是以string的方式保存在任务基本类型/任务类型目录下的文件
	 * @param taskType
	 * @return
	 * @throws Exception
	 */
	public List<ScheduleTaskItem> loadAllTaskItem(String taskType) throws Exception {
		List<ScheduleTaskItem> result = new ArrayList<ScheduleTaskItem>();
		String baseTaskType = TBScheduleManager.splitBaseTaskTypeFromTaskType(taskType);
		String zkPath = this.PATH_BaseTaskType+"/" + baseTaskType +"/" + taskType + "/" + this.PATH_TaskItem;
		if(this.getZooKeeper().exists(zkPath, false)==null){
		    return result;	
		}
		List<String> taskItems = this.getZooKeeper().getChildren(zkPath, false);
		Collections.sort(taskItems);
		for(String taskItem:taskItems){
			ScheduleTaskItem info = new ScheduleTaskItem();
			info.setTaskType(taskType);
			info.setTaskItem(taskItem);
			String zkTaskItemPath = zkPath + "/" + taskItem;
			byte[] curContent = this.getZooKeeper().getData(zkTaskItemPath+"/cur_server",false,null);
			if(curContent != null){
			    info.setCurrentScheduleServer(new String(curContent));
			}
			byte[] reqContent = this.getZooKeeper().getData(zkTaskItemPath+"/req_server",false,null);
			if(reqContent != null){
			    info.setRequestScheduleServer(new String(reqContent));
			}
			byte[] stsContent = this.getZooKeeper().getData(zkTaskItemPath+"/sts",false,null);
			if(stsContent != null){
			    info.setSts(ScheduleTaskItem.TaskItemSts.valueOf(new String(stsContent)));
			}
			byte[] parameterContent = this.getZooKeeper().getData(zkTaskItemPath+"/parameter",false,null);
			if(parameterContent != null){
			    info.setDealParameter(new String(parameterContent));
			}
			byte[] dealDescContent = this.getZooKeeper().getData(zkTaskItemPath+"/deal_desc",false,null);
			if(dealDescContent != null){
			    info.setDealDesc(new String(dealDescContent));
			}
			result.add(info);
		}
		return result;
		
	}
	public ScheduleTaskType loadTaskTypeBaseInfo(String baseTaskType)
			throws Exception {
		String zkPath = this.PATH_BaseTaskType + "/" + baseTaskType;
		if(this.getZooKeeper().exists(zkPath, false) == null){
			return null;
		}
		String valueString= new String(this.getZooKeeper().getData(zkPath, false,null));
		ScheduleTaskType result = (ScheduleTaskType)this.gson.fromJson(valueString, ScheduleTaskType.class);
		return result;
	}

	@Override
	public List<ScheduleTaskType> getAllTaskTypeBaseInfo() throws Exception {
		String zkPath = this.PATH_BaseTaskType;
		List<ScheduleTaskType> result = new ArrayList<ScheduleTaskType>();
		for(String name:this.getZooKeeper().getChildren(zkPath,false)){
			result.add(this.loadTaskTypeBaseInfo(name));
		}
		return result;
	}

	@Override
	public void clearTaskType(String baseTaskType) throws Exception {
		//清除所有的Runtime TaskType		
		String zkPath =this.PATH_BaseTaskType+"/" + baseTaskType; 
		List<String> list = this.getZooKeeper().getChildren(zkPath,false);
		for (String name : list) {
			ZKTools.deleteTree(this.getZooKeeper(),zkPath + "/" + name);
		}
	}

	@Override
	public List<ScheduleTaskTypeRunningInfo> getAllTaskTypeRunningInfo(
			String baseTaskType) throws Exception {
		List<ScheduleTaskTypeRunningInfo> result = new ArrayList<ScheduleTaskTypeRunningInfo>();
		String zkPath =this.PATH_BaseTaskType+"/" + baseTaskType; 
		if(this.getZooKeeper().exists(zkPath, false)==null){
			return result;
		}
		List<String> list = this.getZooKeeper().getChildren(zkPath,false);
		Collections.sort(list);
		
		for(String name:list){
			ScheduleTaskTypeRunningInfo info = new ScheduleTaskTypeRunningInfo();
			info.setBaseTaskType(baseTaskType);
			info.setTaskType(name);
			info.setOwnSign(TBScheduleManager.splitOwnsignFromTaskType(name));
			result.add(info);
		}
		return result;
	}

	@Override
	public void deleteTaskType(String baseTaskType) throws Exception {
		ZKTools.deleteTree(this.getZooKeeper(),this.PATH_BaseTaskType + "/" + baseTaskType);
	}

	@Override
	public List<ScheduleServer> selectScheduleServer(String baseTaskType,
			String ownSign, String ip, String orderStr) throws Exception {
		List<String> names = new ArrayList<String>();
		if(baseTaskType != null && ownSign != null){
			names.add(baseTaskType +"$"+ ownSign) ;
		}else if(baseTaskType != null && ownSign == null){
			if(this.getZooKeeper().exists(this.PATH_BaseTaskType+"/" + baseTaskType,false) != null){
				for(String name:this.getZooKeeper().getChildren(this.PATH_BaseTaskType+"/" + baseTaskType,false)){
					names.add(name);
				}
			}
		}else if(baseTaskType == null){
			for(String name:this.getZooKeeper().getChildren(this.PATH_BaseTaskType,false)){
				if(ownSign != null){
					names.add(name + "$" + ownSign);
				}else{
					for(String str:this.getZooKeeper().getChildren(this.PATH_BaseTaskType+"/" + name,false)){
						names.add(str);
					}
				}
			}
		}
		List<ScheduleServer> result= new ArrayList<ScheduleServer>();
		for(String name:names){
			List<ScheduleServer> tempList = this.selectAllValidScheduleServer(name);
			if(ip == null){
				result.addAll(tempList);
			}else{
				for(ScheduleServer server:tempList){
					if(ip.equals(server.getIp())){
						result.add(server);
					}
				}
			}
		}
		Collections.sort(result,new ScheduleServerComparator(orderStr));
		//排序
		return result;
	}

	@Override
	public List<ScheduleServer> selectHistoryScheduleServer(
			String baseTaskType, String ownSign, String ip, String orderStr)
			throws Exception {
		throw new Exception("没有实现的方法");
	}

	@Override
	/**
	 * 供manager调用重新装载任务
	 */
	public List<TaskItemDefine> reloadDealTaskItem(String taskType, String uuid)
			throws Exception {
		 String baseTaskType = TBScheduleManager.splitBaseTaskTypeFromTaskType(taskType);
		 String zkPath = this.PATH_BaseTaskType + "/" + baseTaskType + "/" + taskType + "/" + this.PATH_TaskItem;
		 
		 List<String> taskItems = this.getZooKeeper().getChildren(zkPath, false);
		 Collections.sort(taskItems);
		 
		 List<TaskItemDefine> result = new ArrayList<TaskItemDefine>();
		 for(String name:taskItems){
			 //Zk+name 就是任务队列名，任务队列名目录下有对个子目录：目前分配的manager，请求次队列的manager等等
			 //当前任务分配给哪个server了
			byte[] value = this.getZooKeeper().getData(zkPath + "/" + name + "/cur_server",false,null);
			 //这个任务是否分配给当前的Manager
			if(value != null && uuid.equals(new String(value))){
				TaskItemDefine item = new TaskItemDefine();
				item.setTaskItemId(name);
				byte[] parameterValue = this.getZooKeeper().getData(zkPath + "/" + name + "/parameter",false,null);
				if(parameterValue != null){
					item.setParameter(new String(parameterValue));
				}
				result.add(item);
			}
		 }
		 return result;
	}
	/**
	 * 释放自己把持，别人申请的队列
	 *
	 * @param taskType
	 * @param uuid
	 * @return
	 * @throws Exception
	 */
	@Override
	//如果真的发生了释放队列操作，负责taskType类型的所有Server需要重新加载任务
	public void releaseDealTaskItem(String taskType, String uuid) throws Exception {
		 String baseTaskType = TBScheduleManager.splitBaseTaskTypeFromTaskType(taskType);
		 String zkPath = this.PATH_BaseTaskType + "/" + baseTaskType + "/" + taskType + "/" + this.PATH_TaskItem;
		 boolean isModify = false;
		 for(String name:this.getZooKeeper().getChildren(zkPath, false)){
			byte[] curServerValue = this.getZooKeeper().getData(zkPath + "/" + name + "/cur_server",false,null);
			byte[] reqServerValue = this.getZooKeeper().getData(zkPath + "/" + name + "/req_server",false,null);
			if(reqServerValue != null && curServerValue != null && uuid.equals(new String(curServerValue))==true){
				this.getZooKeeper().setData(zkPath + "/" + name + "/cur_server",reqServerValue,-1);
				this.getZooKeeper().setData(zkPath + "/" + name + "/req_server",null,-1);		
				isModify = true;
			}
		 }
		 if(isModify == true){ //设置需要所有的服务器重新装载任务
			 this.updateReloadTaskItemFlag(taskType);
		 }
	}

	@Override
	public int queryTaskItemCount(String taskType) throws Exception {
		 String baseTaskType = TBScheduleManager.splitBaseTaskTypeFromTaskType(taskType);
		 String zkPath = this.PATH_BaseTaskType + "/" + baseTaskType + "/" + taskType + "/" + this.PATH_TaskItem;
		 return this.getZooKeeper().getChildren(zkPath, false).size();
	}
	public void clearExpireTaskTypeRunningInfo(String baseTaskType,String serverUUID, double expireDateInternal) throws Exception {
		for (String name : this.getZooKeeper().getChildren(this.PATH_BaseTaskType + "/" + baseTaskType, false)) {
			Stat stat = this.getZooKeeper().exists(this.PATH_BaseTaskType + "/" + baseTaskType + "/" + name, false);
			if (getSystemTime() - stat.getMtime() > (long) (expireDateInternal * 24 * 3600 * 1000)) {
				ZKTools.deleteTree(this.getZooKeeper(),this.PATH_BaseTaskType +"/" + baseTaskType + "/" + name);
			}
		}
	}
	@Override
	//将过期的任务从zookeeper目录中清除，所以任务重新分配。不会继续分配任务给不在列表中的server，
	// 死掉的server自己清理自己的队列，恢复后重新注册自己，通知新增server
	//leader在下个心跳周期重新分配任务
	public int clearExpireScheduleServer(String taskType,long expireTime) throws Exception {
		 int result =0;
		 String baseTaskType = TBScheduleManager.splitBaseTaskTypeFromTaskType(taskType);
		 String zkPath = this.PATH_BaseTaskType + "/" + baseTaskType 
		        + "/" + taskType + "/" + this.PATH_Server;
		 if(this.getZooKeeper().exists(zkPath,false)== null){
			 String tempPath =this.PATH_BaseTaskType + "/" + baseTaskType   + "/" + taskType;
			 if(this.getZooKeeper().exists(tempPath,false)== null){
				 this.getZooKeeper().create(tempPath, null, this.zkManager.getAcl(), CreateMode.PERSISTENT);
			 }
			 this.getZooKeeper().create(zkPath, null, this.zkManager.getAcl(), CreateMode.PERSISTENT);
		 }
		for (String name : this.getZooKeeper().getChildren(zkPath, false)) {
			try {
				//比较心跳过期时间
				Stat stat = this.getZooKeeper().exists(zkPath + "/" + name,false);
				//server死亡
				if (getSystemTime() - stat.getMtime() > expireTime) {
					ZKTools.deleteTree(this.getZooKeeper(), zkPath + "/" + name);
					result++;

				}
			} catch (Exception e) {
				// 当有多台服务器时，存在并发清理的可能，忽略异常
				result++;
			}
		}
		return result;
	}

	@Override
	public int clearTaskItem(String taskType,
			List<String> serverList) throws Exception {
		 String baseTaskType = TBScheduleManager.splitBaseTaskTypeFromTaskType(taskType);
		 String zkPath = this.PATH_BaseTaskType + "/" + baseTaskType + "/" + taskType + "/" + this.PATH_TaskItem;

		 int result =0;
		 for(String name:this.getZooKeeper().getChildren(zkPath, false)){
			byte[] curServerValue = this.getZooKeeper().getData(zkPath + "/" + name + "/cur_server",false,null);
			if(curServerValue != null){
			   String curServer = new String(curServerValue);
			   boolean isFind = false;
			   for(String server : serverList){
				   if(curServer.equals(server)){
					   isFind = true;
				       break;
				   }
			   }
			   if(isFind == false){
				   this.getZooKeeper().setData(zkPath + "/" + name + "/cur_server",null,-1);	
				   result = result + 1;
			   }
			}else{
				result = result + 1;
			}
		 }
		 return result;
	}
	//查找可以处理某种任务类型的server(没考虑心跳)
	public List<String> loadScheduleServerNames(String taskType)throws Exception{
		 String baseTaskType = TBScheduleManager.splitBaseTaskTypeFromTaskType(taskType);
		 String zkPath = this.PATH_BaseTaskType + "/" + baseTaskType + "/" + taskType + "/" + this.PATH_Server;
		 if(this.getZooKeeper().exists(zkPath, false)== null){
			 return new ArrayList<String>();
		 }
		 List<String> serverList = this.getZooKeeper().getChildren(zkPath, false);
		 Collections.sort(serverList,new Comparator<String>(){
				public int compare(String u1, String u2) {
					return u1.substring(u1.lastIndexOf("$") + 1).compareTo(
							u2.substring(u2.lastIndexOf("$") + 1));
				}
			});
		 return serverList;
	}

	@Override
	public List<ScheduleServer> selectAllValidScheduleServer(String taskType)
			throws Exception {
		List<ScheduleServer> result = new ArrayList<ScheduleServer>();
		 String baseTaskType = TBScheduleManager.splitBaseTaskTypeFromTaskType(taskType);
		 String zkPath = this.PATH_BaseTaskType + "/" + baseTaskType + "/" + taskType + "/" + this.PATH_Server;
		 if(this.getZooKeeper().exists(zkPath, false)== null){
			 return result;
		 }
		 List<String> serverList = this.getZooKeeper().getChildren(zkPath, false);
		 Collections.sort(serverList,new Comparator<String>(){
				public int compare(String u1, String u2) {
					return u1.substring(u1.lastIndexOf("$") + 1).compareTo(
							u2.substring(u2.lastIndexOf("$") + 1));
				}
			});
		for(String name:serverList){
			try{
			String valueString= new String(this.getZooKeeper().getData(zkPath + "/"+ name, false,null));
			ScheduleServer server = (ScheduleServer)this.gson.fromJson(valueString,ScheduleServer.class);
			server.setCenterServerTime(new Timestamp(this.getSystemTime()));
			result.add(server);
			}catch(Exception e){
				log.debug(e);
			}
		}
		return result;
	}
	
	public List<ScheduleServer> selectScheduleServerByManagerFactoryUUID(String factoryUUID)
			throws Exception {
		 List<ScheduleServer> result = new ArrayList<ScheduleServer>();
		 for(String baseTaskType: this.getZooKeeper().getChildren(this.PATH_BaseTaskType,false)){
			for(String taskType: this.getZooKeeper().getChildren(this.PATH_BaseTaskType+"/"+baseTaskType,false)){
				String zkPath =  this.PATH_BaseTaskType+"/"+baseTaskType+"/"+taskType+"/"+this.PATH_Server;
				for(String uuid: this.getZooKeeper().getChildren(zkPath,false)){
					String valueString= new String(this.getZooKeeper().getData(zkPath + "/"+ uuid, false,null));
					ScheduleServer server = (ScheduleServer)this.gson.fromJson(valueString,ScheduleServer.class);
					server.setCenterServerTime(new Timestamp(this.getSystemTime()));
					if (server.getManagerFactoryUUID().equals(factoryUUID)) {
						result.add(server);
					}
				}		  
			}
		 }
		 Collections.sort(result,new Comparator<ScheduleServer>(){
				public int compare(ScheduleServer u1, ScheduleServer u2) {
					int result = u1.getTaskType().compareTo(u2.getTaskType());
					if(result == 0){
						String s1 = u1.getUuid();
						String s2 = u2.getUuid();
						result = s1.substring(s1.lastIndexOf("$") + 1).compareTo(
								s2.substring(s2.lastIndexOf("$") + 1));
					}
					return result;
				}
			});
		return result;
	}	
	//选举leader server
	public String getLeader(List<String> serverList){
		if(serverList == null || serverList.size() ==0){
			return "";
		}
		long no = Long.MAX_VALUE;
		long tmpNo = -1;
		String leader = null;
    	for(String server:serverList){
			//server 名称组成应该是XXXXXX$131231231312313,选择数字最大的作为leader
    		tmpNo =Long.parseLong( server.substring(server.lastIndexOf("$")+1));
    		if(no > tmpNo){
    			no = tmpNo;
    			leader = server;
    		}
    	}
    	return leader;
    }
    public boolean isLeader(String uuid,List<String> serverList){
    	return uuid.equals(getLeader(serverList));
    }
	@Override
	public void assignTaskItem(String taskType, String currentUuid,
			List<String> serverList) throws Exception {
		 if(this.isLeader(currentUuid,serverList)==false){
			 if(log.isDebugEnabled()){
			   log.debug(currentUuid +":不是负责任务分配的Leader,直接返回");
			 }
			 return;
		 }
		 if(log.isDebugEnabled()){
			   log.debug(currentUuid +":开始重新分配任务......");
		 }		
		 if(serverList.size()<=0){
			 //在服务器动态调整的时候，可能出现服务器列表为空的清空
			 return;
		 }
		 String baseTaskType = TBScheduleManager.splitBaseTaskTypeFromTaskType(taskType);
		 String zkPath = this.PATH_BaseTaskType + "/" + baseTaskType + "/" + taskType + "/" + this.PATH_TaskItem;
		 int point =0;
		 List<String> children = this.getZooKeeper().getChildren(zkPath, false);
		 Collections.sort(children);
		 int unModifyCount =0;
		 for(String name:children){
			byte[] curServerValue = this.getZooKeeper().getData(zkPath + "/" + name + "/cur_server",false,null);
			byte[] reqServerValue = this.getZooKeeper().getData(zkPath + "/" + name + "/req_server",false,null);
			if(curServerValue == null){
				this.getZooKeeper().setData(zkPath + "/" + name + "/cur_server",serverList.get(point).getBytes(),-1);
				this.getZooKeeper().setData(zkPath + "/" + name + "/req_server",null,-1);
			}else if(new String(curServerValue).equals(serverList.get(point))==true && reqServerValue == null ){
				//不需要做任何事情
				unModifyCount = unModifyCount + 1;
			}else{
				this.getZooKeeper().setData(zkPath + "/" + name + "/req_server",serverList.get(point).getBytes(),-1);
			}
			 //分配任务的逻辑，平均分配给list中的每个server从头到位的分配任务
			point = (point  + 1) % serverList.size();
		 }	
		 //有过任务重分配，则全部server在下个心跳时，重新拉取任务
		 if(unModifyCount < children.size()){ //设置需要所有的服务器重新装载任务
			 this.updateReloadTaskItemFlag(taskType);
		 }
		 if(log.isDebugEnabled()){
			 StringBuffer buffer = new StringBuffer();
			 for(ScheduleTaskItem taskItem: this.loadAllTaskItem(taskType)){
				buffer.append("\n").append(taskItem.toString());
			 }
			 log.debug(buffer);
		 }
	}
	public void registerScheduleServer(ScheduleServer server) throws Exception {
		if(server.isRegister() == true){
			throw new Exception(server.getUuid() + " 被重复注册");
		}
		String zkPath = this.PATH_BaseTaskType + "/" + server.getBaseTaskType() +"/" + server.getTaskType();
		if (this.getZooKeeper().exists(zkPath, false) == null) {
			this.getZooKeeper().create(zkPath, null, this.zkManager.getAcl(),CreateMode.PERSISTENT);
		}
		zkPath = zkPath +"/" + this.PATH_Server;
		if (this.getZooKeeper().exists(zkPath, false) == null) {
			this.getZooKeeper().create(zkPath, null, this.zkManager.getAcl(),CreateMode.PERSISTENT);
		}
		String realPath = null;
		//此处必须增加UUID作为唯一性保障
		String zkServerPath = zkPath + "/" + server.getTaskType() + "$"+ server.getIp() + "$"
				+ (UUID.randomUUID().toString().replaceAll("-", "").toUpperCase())+"$";
		realPath = this.getZooKeeper().create(zkServerPath, null, this.zkManager.getAcl(),CreateMode.PERSISTENT_SEQUENTIAL);
		server.setUuid(realPath.substring(realPath.lastIndexOf("/") + 1));
		
		Timestamp heartBeatTime = new Timestamp(ScheduleUtil.getCurrentTimeMillis());
		server.setHeartBeatTime(heartBeatTime);
		
		String valueString = this.gson.toJson(server);		
		this.getZooKeeper().setData(realPath,valueString.getBytes(),-1);
		server.setRegister(true);
	}
	//向zookeeper更新心跳信息
	public boolean refreshScheduleServer(ScheduleServer server) throws Exception {
		Timestamp heartBeatTime = new Timestamp(ScheduleUtil
				.getCurrentTimeMillis());
    	String zkPath = this.PATH_BaseTaskType + "/" + server.getBaseTaskType() + "/" + server.getTaskType() 
    	    + "/" + this.PATH_Server +"/" + server.getUuid();
    	if(this.getZooKeeper().exists(zkPath, false)== null){
    		//数据可能被清除，先清除内存数据后，重新注册数据
    		server.setRegister(false);
    		return false;
    	}else{
    		Timestamp oldHeartBeatTime = server.getHeartBeatTime();
    		server.setHeartBeatTime(heartBeatTime);
    		server.setVersion(server.getVersion() + 1);
    		String valueString = this.gson.toJson(server);
    		try{
    			this.getZooKeeper().setData(zkPath,valueString.getBytes(),-1);
    		}catch(Exception e){
    			//恢复上次的心跳时间
    			server.setHeartBeatTime(oldHeartBeatTime);
    			server.setVersion(server.getVersion() - 1);
    			throw e;
    		}
    		return true;
    	}
	}
	
	public void unRegisterScheduleServer(String taskType,String serverUUID) throws Exception {
		 String baseTaskType = TBScheduleManager.splitBaseTaskTypeFromTaskType(taskType);
		 String zkPath = this.PATH_BaseTaskType + "/" + baseTaskType + "/" + taskType + "/" + this.PATH_Server  + "/"+ serverUUID;
		 if(this.getZooKeeper().exists(zkPath, false) != null){
	    	this.getZooKeeper().delete(zkPath, -1);
		 }
	}
	
	@Override
	public void pauseAllServer(String baseTaskType) throws Exception {
		ScheduleTaskType taskType = this.loadTaskTypeBaseInfo(baseTaskType);
		taskType.setSts(ScheduleTaskType.STS_PAUSE);
		this.updateBaseTaskType(taskType);
	}

	@Override
	public void resumeAllServer(String baseTaskType) throws Exception {
		ScheduleTaskType taskType = this.loadTaskTypeBaseInfo(baseTaskType);
		taskType.setSts(ScheduleTaskType.STS_RESUME);
		this.updateBaseTaskType(taskType);
	}

	
	public long getSystemTime(){
		return System.currentTimeMillis();
	}

}

class ScheduleServerComparator implements Comparator<ScheduleServer>{
	String[] orderFields;
	public ScheduleServerComparator(String aOrderStr){
		if(aOrderStr != null){
			orderFields = aOrderStr.toUpperCase().split(",");
		}else{
			orderFields = "TASK_TYPE,OWN_SIGN,REGISTER_TIME,HEARTBEAT_TIME,IP".toUpperCase().split(",");
		}
	}
	public int compareObject(String o1,String o2){
		if(o1==null && o2 == null){
			return 0;
		}else if(o1 != null){
			return o1.compareTo(o2);
		}else {
			return -1;
		}
	}
	public int compareObject(Timestamp o1,Timestamp o2){
		if(o1==null && o2 == null){
			return 0;
		}else if(o1 != null){
			return o1.compareTo(o2);
		}else {
			return -1;
		}
	}
	public int compare(ScheduleServer o1, ScheduleServer o2) {
		int result = 0;
		for(String name : orderFields){
			if(name.equals("TASK_TYPE")){
				result =compareObject( o1.getTaskType(),o2.getTaskType());
				if(result != 0){
				   return result;
				}
			}else if(name.equals("OWN_SIGN")){
				result = compareObject(o1.getOwnSign(),o2.getOwnSign());
				if(result != 0){
				   return result;
				}
			}else if(name.equals("REGISTER_TIME")){
				result = compareObject(o1.getRegisterTime(),o2.getRegisterTime());
				if(result != 0){
					return result;
				}
			}else if(name.equals("HEARTBEAT_TIME")){
				result =compareObject( o1.getHeartBeatTime(),o2.getHeartBeatTime());
				if(result != 0){
					return result;
				}
			}else if(name.equals("IP")){
				result = compareObject(o1.getIp(),o2.getIp());
				if(result != 0){
					return result;
				}
			}else if(name.equals("MANAGER_FACTORY")){
				result = compareObject(o1.getManagerFactoryUUID(),o2.getManagerFactoryUUID());
				if(result != 0){
					return result;
				}
			}
		}
		return result;
	}
}
class TimestampTypeAdapter implements JsonSerializer<Timestamp>, JsonDeserializer<Timestamp>{   
    public JsonElement serialize(Timestamp src, Type arg1, JsonSerializationContext arg2) {   
    	DateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");   
        String dateFormatAsString = format.format(new Date(src.getTime()));   
        return new JsonPrimitive(dateFormatAsString);   
    }   
  
    public Timestamp deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {   
        if (!(json instanceof JsonPrimitive)) {   
            throw new JsonParseException("The date should be a string value");   
        }   
  
        try {   
        	DateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");   
            Date date = (Date) format.parse(json.getAsString());   
            return new Timestamp(date.getTime());   
        } catch (Exception e) {   
            throw new JsonParseException(e);   
        }   
    }   

}  



 