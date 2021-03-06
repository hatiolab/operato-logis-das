package operato.logis.das.service.impl;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import operato.logis.das.query.store.DasQueryStore;
import operato.logis.das.service.util.DasBatchJobConfigUtil;
import xyz.anythings.base.LogisConstants;
import xyz.anythings.base.entity.JobBatch;
import xyz.anythings.base.entity.JobConfigSet;
import xyz.anythings.base.entity.JobInstance;
import xyz.anythings.base.entity.Order;
import xyz.anythings.base.entity.OrderPreprocess;
import xyz.anythings.base.entity.Rack;
import xyz.anythings.base.entity.WorkCell;
import xyz.anythings.base.service.api.IIndicationService;
import xyz.anythings.base.service.api.IInstructionService;
import xyz.anythings.base.service.impl.AbstractInstructionService;
import xyz.anythings.base.service.impl.LogisServiceDispatcher;
import xyz.anythings.base.util.LogisBaseUtil;
import xyz.anythings.gw.entity.IndConfigSet;
import xyz.anythings.sys.event.model.SysEvent;
import xyz.anythings.sys.util.AnyEntityUtil;
import xyz.anythings.sys.util.AnyOrmUtil;
import xyz.anythings.sys.util.AnyValueUtil;
import xyz.elidom.dbist.dml.Filter;
import xyz.elidom.dbist.dml.Query;
import xyz.elidom.orm.OrmConstants;
import xyz.elidom.sys.util.MessageUtil;
import xyz.elidom.sys.util.ThrowUtil;
import xyz.elidom.util.BeanUtil;
import xyz.elidom.util.ValueUtil;

/**
 * 출고용 작업지시 서비스
 * 
 * @author shortstop
 */
@Component("dasInstructionService")
public class DasInstructionService extends AbstractInstructionService implements IInstructionService {
	
	/**
	 * 커스텀 서비스 - 작업 지시 전 처리
	 */
	private static final String DIY_PRE_BATCH_START = "diy-das-pre-batch-start";
	/**
	 * 커스텀 서비스 - 작업 지시 후 처리
	 */
	private static final String DIY_POST_BATCH_START = "diy-das-post-batch-start";
	/**
	 * 커스텀 서비스 - 배치 병합 전 처리
	 */
	private static final String DIY_PRE_MERGE_BATCH = "diy-das-pre-merge-batch";
	/**
	 * 커스텀 서비스 - 배치 병합 후 처리
	 */
	private static final String DIY_POST_MERGE_BATCH = "diy-das-post-merge-batch";
	/**
	 * 커스텀 서비스 - 작업 지시 취소 전 처리
	 */
	private static final String DIY_PRE_CANCEL_BATCH = "diy-das-pre-cancel-batch";
	/**
	 * 커스텀 서비스 - 작업 지시 취소 후 처리
	 */
	private static final String DIY_POST_CANCEL_BATCH = "diy-das-post-cancel-batch";

	/**
	 * 출고 작업 쿼리 스토어
	 */
	@Autowired
	private DasQueryStore dasQueryStore;
	
	@Override
	public void targetClassing(JobBatch batch, Object... params) {
		Map<String,Object> condition = ValueUtil.newMap("domainId,batchId", batch.getDomainId(), batch.getId());
		// 대상 분류 
		this.queryManager.executeBySql("call sp_order_classification(:domainId,:batchId)", condition);
		// 회차 분류 
		this.queryManager.executeBySql("call sp_order_optimization(:domainId,:batchId)", condition);
	}
	
	@Override
	public Map<String, Object> searchInstructionData(JobBatch batch, Object... params) {
		String sql = this.dasQueryStore.getDasInstructionSummaryDataQuery();
		Long domainId = batch.getDomainId();
		Map<String,Object> param = ValueUtil.newMap("domainId,batchId", domainId, batch.getId());
		Map<?, ?> cntResult = this.queryManager.selectBySql(sql, param, Map.class);
 
		Query condition = AnyOrmUtil.newConditionForExecution(domainId, 0, 0);
		condition.addFilter("batchId", batch.getId());
		condition.addFilter("equipCd", "is_not_null", LogisConstants.EMPTY_STRING);
		condition.addFilter("subEquipCd", "is_not_null", LogisConstants.EMPTY_STRING);
		condition.addOrder("cellAssgnCd", true);
		List<OrderPreprocess> preprocesses = this.queryManager.selectList(OrderPreprocess.class, condition);

		return ValueUtil.newMap("list,totalOrderCnt,totalSkuCnt,totalPcs", preprocesses, cntResult.get("order_cnt"), cntResult.get("sku_cnt"), cntResult.get("total_pcs"));
	}
	
	@Override
	public int instructBatch(JobBatch batch, List<String> equipCdList, Object... params) {
		int instructCount = 0;

		if(this.beforeInstructBatch(batch, equipCdList)) {
			instructCount += this.doInstructBatch(batch, equipCdList);
			this.afterInstructBatch(batch, equipCdList);
		}

		return instructCount;
	}

	@Override
	public int instructTotalpicking(JobBatch batch, List<String> equipIdList, Object... params) {
		// 출고에서는 사용 안 함
		return 0;
	}

	@Override
	public int mergeBatch(JobBatch mainBatch, JobBatch newBatch, Object... params) {
		int mergeCount = this.beforeMergeBatch(mainBatch, newBatch, params);
		this.doMergeBatch(mainBatch, newBatch, params);
		this.afterMergeBatch(mainBatch, newBatch, params);
		return mergeCount;
	}
	
	/**
	 * 배치 병합 전 체크
	 * 
	 * @param mainBatch
	 * @param newBatch
	 * @param params
	 * @return
	 */
	private int beforeMergeBatch(JobBatch mainBatch, JobBatch newBatch, Object... params) {
		// 1. 병합 전 커스텀 처리
		this.customService.doCustomService(mainBatch.getDomainId(), DIY_PRE_MERGE_BATCH, ValueUtil.newMap("mainBatch,newBatch", mainBatch, newBatch));
		
		// 2. 메인 배치 상태 체크
		if(ValueUtil.isNotEqual(mainBatch.getStatus(), JobBatch.STATUS_RUNNING)) {
			// 메인 작업배치가 진행 중인 상태에서만 병합 가능합니다
			throw ThrowUtil.newValidationErrorWithNoLog(true, "ALLOWED_MERGE_WHEN_MAIN_BATCH_RUN");
		}
		
		// 3. 병합 배치 상태 체크
		if(ValueUtil.isNotEqual(newBatch.getStatus(), JobBatch.STATUS_WAIT)) {
			// 병합 대상 작업배치가 주문가공대기 상태에서만 가능합니다
			throw ThrowUtil.newValidationErrorWithNoLog(true, "ALLOWED_MERGE_WHEN_TARGET_BATCH_WAIT");
		}
		
		// 4. 메인 배치에 현재 점등이 되어 있는 작업이 있는지 체크
		Map<String, Object> qParams = ValueUtil.newMap("domainId,batchId,status,equipType,equipCd", mainBatch.getDomainId(), mainBatch.getId(), LogisConstants.JOB_STATUS_PICKING, mainBatch.getEquipType(), mainBatch.getEquipCd());
		int indOnCount = this.queryManager.selectSize(JobInstance.class, qParams);
		
		if(indOnCount > 0) {
			throw ThrowUtil.newValidationErrorWithNoLog(true, "NOT_ALLOWED_MERGE_MAIN_BATCH_IND_ON");
		}
		
		// 5. 메인 배치에 완료 셀 + 빈 셀 개수 카운트
		String sql = this.dasQueryStore.getDasEmptyCellCountForMergeQuery();
		int mainBatchEmptyCells = this.queryManager.selectBySql(sql, qParams, Integer.class);
		
		// 6. 병합 배치에는 있고 메인 배치에는 없는 신규 상품 수 카운트
		qParams.put("mergeBatchId", newBatch.getId());
		sql = this.dasQueryStore.getDasNewCellCountForMergeQuery();
		int newSkuCount = this.queryManager.selectBySql(sql, qParams, Integer.class);
		
		// 7. 신규 상품 개수가 완료 + 빈 셀 카운트 보다 크면 병합 불가
		if(newSkuCount > mainBatchEmptyCells) {
			// 병합하려는 배치의 신규 상품 수량이 빈 셀 보다 많아서 병합이 불가합니다.
			throw ThrowUtil.newValidationErrorWithNoLog(true, "NOT_ALLOWED_MERGE_MANY_SKU");
		}
		
		// 8. 병합할 신규 셀 수
		return newSkuCount;
	}
	
	/**
	 * 배치 병합 처리
	 * 
	 * @param mainBatch
	 * @param newBatch
	 * @param params
	 */
	private void doMergeBatch(JobBatch mainBatch, JobBatch newBatch, Object... params) {
		// 1. 신규 상품 & 셀 리스트 조회 및 생성
		String sql = this.dasQueryStore.getDasNewWorkCellsForMergeQuery();
		Map<String, Object> qParams = ValueUtil.newMap("domainId,batchId,mainBatchId", newBatch.getDomainId(), newBatch.getId(), mainBatch.getId());
		List<WorkCell> newWorkCellList = this.queryManager.selectListBySql(sql, qParams, WorkCell.class, 0, 0);
		this.queryManager.insertBatch(newWorkCellList);
		
		// 2. 신규 상품 & 셀 기반으로 WorkCell 업데이트 혹은 추가하면서 상태가 ENDING, ENDED인 것은 NULL로 업데이트
		sql = "UPDATE WORK_CELLS SET STATUS = null, JOB_INSTANCE_ID = null WHERE DOMAIN_ID = :domainId and BATCH_ID = :mainBatchId AND STATUS IN ('ENDING', 'ENDED') AND CLASS_CD IN (SELECT DISTINCT CLASS_CD FROM ORDERS WHERE DOMAIN_ID = :domainId AND BATCH_ID = :batchId)";
		this.queryManager.executeBySql(sql, qParams);
		
		// 3. 병합 배치의 작업 정보 생성을 위한 작업 조회
		sql = this.dasQueryStore.getDasNewJobInstancesByBatchQuery();
		List<JobInstance> newJobList = this.queryManager.selectListBySql(sql, qParams, JobInstance.class, 0, 0);
		
		// 4. 메인 배치의 작업 정보 생성을 위한 작업 조회
		Query condition = AnyOrmUtil.newConditionForExecution(newBatch.getDomainId(), 0, 0);
		condition.addFilter("batchId", mainBatch.getId());
		condition.addFilter("status", "in", LogisConstants.JOB_STATUS_WIP);
		List<JobInstance> jobList = this.queryManager.selectList(JobInstance.class, condition);
		
		// 5. 생성, 업데이트 작업 리스트 컨테이너 정의
		List<JobInstance> updateJobs = new ArrayList<JobInstance>();

		// 6. 업데이트할 작업 리스트 추출
		for(JobInstance job : jobList) {
			JobInstance newJob = this.extractJobInstance(job, newJobList);
			if(newJob != null) {
				job.setPickQty(job.getPickQty() + newJob.getPickQty());
				updateJobs.add(job);
				newJobList.remove(job);
			}
		}
		
		// 7. 작업 업데이트
		this.queryManager.updateBatch(updateJobs);
		
		// 8. 작업 인서트
		this.queryManager.insertBatch(newJobList);
		
		// 9. 병합하려는 배치의 주문을 메인 배치로 병합
		sql = "UPDATE ORDERS SET BATCH_ID = :mainBatchId WHERE DOMAIN_ID = :domainId AND BATCH_ID = :batchId";
		this.queryManager.executeBySql(sql, qParams);
	}
	
	/**
	 * newJobs에서 job과 동일한 classCd를 가진 작업 찾아 리턴
	 * 
	 * @param job
	 * @param newJobs
	 * @return
	 */
	private JobInstance extractJobInstance(JobInstance job, List<JobInstance> newJobs) {
		for(JobInstance newJob : newJobs) {
			if(ValueUtil.isEqualIgnoreCase(job.getClassCd(), newJob.getClassCd()) && ValueUtil.isEqualIgnoreCase(job.getSkuCd(), newJob.getSkuCd())) {
				return newJob;
			}
		}
		
		return null;
	}
	
	/**
	 * 배치 병합 후 처리
	 * 
	 * @param mainBatch
	 * @param newBatch
	 * @param params
	 */
	private void afterMergeBatch(JobBatch mainBatch, JobBatch newBatch, Object... params) {
		// 1. WorkCell의 작업 상태가 ENDING, ENDED 상태인데 작업 병합 후 처리할 분류 작업이 존재하는 셀 조회
		Long domainId = mainBatch.getDomainId();
		String sql = this.dasQueryStore.getDasEndCellOffQuery();
		Map<String, Object> qParams = 
				ValueUtil.newMap("domainId,batchId,cellStatuses,jobStatuses", domainId, mainBatch.getId(), LogisConstants.CELL_JOB_STATUS_END_LIST, LogisConstants.JOB_STATUS_WIP);
		
		// 2. 표시기 강제 소등 (END 표시를 지운다.)
		IIndicationService indSvc = this.serviceDispatcher.getIndicationService(mainBatch);
		List<WorkCell> indOffCells = this.queryManager.selectListBySql(sql, qParams, WorkCell.class, 0, 0);
		
		for(WorkCell cell : indOffCells) {
			indSvc.indicatorOff(domainId, mainBatch.getStageCd(), cell.getIndCd());
		}
		
		// 3. 병합 후 처리 커스텀 서비스 호출
		this.customService.doCustomService(mainBatch.getDomainId(), DIY_POST_MERGE_BATCH, ValueUtil.newMap("mainBatch,newBatch", mainBatch, newBatch));
		
		// 4. 작업 병합 이벤트 전송
		this.publishMergingEvent(SysEvent.EVENT_STEP_AFTER, mainBatch, newBatch, null);
	}

	@Override
	public int cancelInstructionBatch(JobBatch batch) {
		int cancelCount = 0;
		
		if(this.beforeCancelInstructionBatch(batch)) {
			cancelCount += this.doCancelInstructionBatch(batch);
			this.afterCancelInstructionBatch(batch);
		}
		
		return cancelCount;
	}
	
	/**
	 * 작업 취소 전 처리 액션
	 * 
	 * @param batch
	 * @return
	 */
	protected boolean beforeCancelInstructionBatch(JobBatch batch) {
		
		// 1. 배치 취소 전 처리 커스텀 서비스 호출
		this.customService.doCustomService(batch.getDomainId(), DIY_PRE_CANCEL_BATCH, ValueUtil.newMap("batch", batch));
		
		// 2. 하나라도 처리되었다면 취소 불가
		Query condition = AnyOrmUtil.newConditionForExecution(batch.getDomainId());
		condition.addFilter("batchId", batch.getId());
		condition.addFilter(new Filter("pickedQty", OrmConstants.GREATER_THAN, 0));
		
		if(this.queryManager.selectSize(JobInstance.class, condition) > 0) {
			// 분류 작업시작 이후여서 취소가 불가능합니다
			throw ThrowUtil.newValidationErrorWithNoLog(true, "NOT_ALLOWED_CANCEL_AFTER_START_JOB");
		}
		
		return true;
	}
	
	/**
	 * 작업 취소 처리 로직
	 * 
	 * @param batch
	 * @return
	 */
	protected int doCancelInstructionBatch(JobBatch batch) {
		// 1. 작업 배치 정보 조회
		Long domainId = batch.getDomainId();
		String rackCd = batch.getEquipCd();
		
		// 2. 배치 호기 코드가 존재하면
		Rack rack = AnyEntityUtil.findEntityBy(domainId, true, Rack.class, null, "domainId,rackCd", domainId, rackCd);
		rack.setBatchId(null);
		rack.setStatus(null);
		this.queryManager.update(rack, "batchId", "status", "updaterId", "updatedAt");
		
		// 3. 작업 배치 정보 업데이트 
		batch.setStatus(JobBatch.STATUS_READY);
		batch.setInstructedAt(null);
		this.queryManager.update(batch, "status", "instructedAt");
		
		// 4. WorkCell 정보 삭제
		Map<String, Object> params = ValueUtil.newMap("domainId,batchId", domainId, batch.getId());
		String sql = "delete from work_cells where domain_id = :domainId and batch_id = :batchId";
		int count = this.queryManager.executeBySql(sql, params);
		
		// 5. 작업 실행 데이터 삭제
		this.queryManager.deleteList(JobInstance.class, params);
		
		return count;
	}	
	
	/**
	 * 작업 취소 후 처리 액션
	 * 
	 * @param batch
	 * @return
	 */
	protected void afterCancelInstructionBatch(JobBatch batch) {
		// 1. 작업 지시 시점에 표시기 점등했다면 표시기 소등
		if(DasBatchJobConfigUtil.isIndOnAssignedCellWhenInstruction(batch)) {
			BeanUtil.get(LogisServiceDispatcher.class).getIndicationService(batch).indicatorOffAll(batch);
		}
		
		// 2. 배치 취소 후 처리 커스텀 서비스 호출
		this.customService.doCustomService(batch.getDomainId(), DIY_POST_CANCEL_BATCH, ValueUtil.newMap("batch", batch));
		
		// 3. 작업 지시 취소 이벤트 전송
		this.publishInstructionCancelEvent(SysEvent.EVENT_STEP_AFTER, batch, null);
	}
	
	/**
	 * 작업 지시 전 처리 액션
	 *
	 * @param batch
	 * @param rackList
	 * @return
	 */
	protected boolean beforeInstructBatch(JobBatch batch, List<String> equipIdList) {
		// 1. 배치 시작 전 처리 커스텀 서비스 호출
		this.customService.doCustomService(batch.getDomainId(), DIY_PRE_BATCH_START, ValueUtil.newMap("batch", batch));

		// 2. 배치 상태가 작업 지시 상태인지 체크
		if(ValueUtil.isNotEqual(batch.getStatus(), JobBatch.STATUS_READY)) {
			// '작업 지시 대기' 상태가 아닙니다
			throw ThrowUtil.newValidationErrorWithNoLog(MessageUtil.getTerm("terms.text.is_not_wait_state", "JobBatch status is not 'READY'"));
		}

		return true;
	}
	
	/**
	 * 작업 지시 처리 로직
	 *
	 * @param batch
	 * @param regionList
	 * @return
	 */
	protected int doInstructBatch(JobBatch batch, List<String> regionList) {
		// 1. 표시기 소등
		this.serviceDispatcher.getIndicationService(batch).indicatorOffAll(batch);
		
		// 2. 배치 내 할당 안 된 주문 가공 정보, 주문 정보를 새로운 배치로 잘라낸다.
		List<OrderPreprocess> preprocesses = this.cutoffNotAssignedOrders(batch);
		
		// 3. 주문 가공 정보로 부터 호기 리스트 조회
		Long domainId = batch.getDomainId();
		List<String> equipCdList = AnyValueUtil.filterValueListBy(preprocesses, "equipCd");
		
		// 4. 랙이 없다면 배치의 랙을 추가
		if(ValueUtil.isEmpty(equipCdList)) {
			equipCdList = ValueUtil.toList(batch.getEquipCd());
		}
		
		Query condition = AnyOrmUtil.newConditionForExecution(domainId);
		condition.addSelect("id", "rackCd", "rackNm", "rackType", "status", "batchId");
		condition.addFilter("rackCd", "in", equipCdList);
		condition.addOrder("rackCd", false);
		List<Rack> rackList = this.queryManager.selectList(Rack.class, condition);

		// 5. 랙 중에 현재 작업 중인 랙이 있는지 체크 
		for(Rack rack : rackList) {
			if(ValueUtil.isNotEmpty(rack.getBatchId())) {
				// 랙에 다른 작업 배치가 할당되어 있습니다
				throw ThrowUtil.newValidationErrorWithNoLog(true, "ASSIGNED_ANOTHER_BATCH_AT_RACK", ValueUtil.toList(rack.getRackNm()));
			}
		}
		
		// 6. 랙 리스트를 돌면서 배치 생성, 주문 가공 정보 업데이트, 주문 정보 업데이트, 작업 정보 생성, WorkCell 정보 생성, 호기 정보 업데이트 처리
		int rackCount = rackList.size();
		for(int i = 0 ; i < rackCount ; i++) {
			// 6-1. 랙 추출
			Rack rack = rackList.get(i);
			// 6-2. 랙 별 주문 가공 데이터 추출
			List<OrderPreprocess> equipPreprocesses = AnyValueUtil.filterListBy(preprocesses, "equipCd", rack.getRackCd());
			// 6-3. 마지막 번째 설비는 메인 배치, 그 외 설비는 새로운 배치를 생성하여 
			String batchId = (i != rackCount - 1) ? LogisBaseUtil.newJobBatchId(domainId, batch.getStageCd()) : batch.getId();
			JobBatch newBatch = this.sliceBatch(batch, batchId, rack, equipPreprocesses);
			// 6-4. 각 배치 별로 작업지시 처리
			this.instructBySlicedBatch(newBatch, rack, equipPreprocesses);
		}
		
		// 7. 주문 가공 정보 배치 ID 업데이트
		AnyOrmUtil.updateBatch(preprocesses, 100, "batchId");
		// 8. 랙 정보 배치 ID 업데이트
		AnyOrmUtil.updateBatch(rackList, 100, "status", "batchId", "jobType");
		// 9. 결과 건수 리턴
		return preprocesses.size();
	}
	
	/**
	 * 작업 지시 후 처리 액션
	 *
	 * @param batch
	 * @param rackList
	 * @return
	 */
	protected boolean afterInstructBatch(JobBatch batch, List<String> equipIdList) {
		// 1. 배치 시작 액션 처리 
		this.serviceDispatcher.getAssortService(batch).batchStartAction(batch);

		// 2. 배치 시작 후 처리 커스텀 서비스 호출
		this.customService.doCustomService(batch.getDomainId(), DIY_POST_BATCH_START, ValueUtil.newMap("batch", batch));
		
		// 3. 작업 지시 이벤트 전송
		this.publishInstructionEvent(SysEvent.EVENT_STEP_AFTER, batch, equipIdList);
		return true;
	}
	
	/**
	 * 배치 내 할당되지 않은 주문을 컷오프 시킨다.
	 * 
	 * @param batch
	 * @return
	 */
	private List<OrderPreprocess> cutoffNotAssignedOrders(JobBatch batch) {
		// 1. 새로운 배치 ID 생성
		String cutoffBatchId = LogisBaseUtil.newJobBatchId(batch.getDomainId(), batch.getStageCd());
		
		// 2. 랙이 할당되지 않은 주문 가공 리스트를 새로운 배치로 업데이트
		Map<String, Object> params = ValueUtil.newMap("domainId,batchId,newBatchId", batch.getDomainId(), batch.getId(), cutoffBatchId);
		String sql = "UPDATE ORDER_PREPROCESSES SET BATCH_ID = :newBatchId WHERE DOMAIN_ID = :domainId AND BATCH_ID = :batchId AND (EQUIP_CD IS NULL OR SUB_EQUIP_CD IS NULL)";
		int updatedCnt = this.queryManager.executeBySql(sql, params);
		
		if(updatedCnt > 0) {
			// 3. 존재한다면 새로운 배치 번호를 얻어서 할당 안 된 주문 가공 및 주문 리스트의 배치 번호 업데이트
			sql = "UPDATE ORDERS SET BATCH_ID = :newBatchId WHERE DOMAIN_ID = :domainId AND BATCH_ID = :batchId AND CLASS_CD IN (SELECT CELL_ASSGN_CD FROM ORDER_PREPROCESSES WHERE DOMAIN_ID = :domainId AND BATCH_ID = :newBatchId)";
			this.queryManager.executeBySql(sql, params);
			
			// 4. 새로운 배치 생성
			JobBatch newBatch = AnyValueUtil.populate(batch, new JobBatch());
			newBatch.setId(cutoffBatchId);
			newBatch.setEquipCd(null);
			newBatch.setEquipNm(null);
			newBatch.setStatus(JobBatch.STATUS_WAIT);
			this.queryManager.insert(newBatch);
		}
		
		// 5. 이전 배치 주문 가공 정보 조회 후 리턴
		Query query = AnyOrmUtil.newConditionForExecution(batch.getDomainId());
		query.addFilter("batchId", batch.getId());
		return this.queryManager.selectList(OrderPreprocess.class, query);
	}
	
	/**
	 * 랙 별 분할된 배치별로 작업 지시를 수행한다. 
	 * 
	 * @param batch
	 * @param rack
	 * @param equipPreprocesses
	 */
	private void instructBySlicedBatch(JobBatch batch, Rack rack, List<OrderPreprocess> equipPreprocesses) {
		// 1. 주문 정보 업데이트 - 배치 ID, 랙 코드, 셀 코드
		this.updateOrdersBy(batch, equipPreprocesses);
		
		// 2. 작업 정보 생성
		this.generateJobInstancesBy(batch);
		
		// 3. WorkCell 정보 생성
		this.generateWorkCellBy(batch, equipPreprocesses);
		
		// 4. Rack 업데이트
		rack.setJobType(batch.getJobType());
		rack.setBatchId(batch.getId());
		rack.setStatus(JobBatch.STATUS_RUNNING);
		
		// 5. 배치별 작업 설정 (JobConfigSet / IndConfigSet 설정)
		if(ValueUtil.isEmpty(batch.getJobConfigSetId())) {
			// 5.1 JobConfigSet 설정
			String jobConfigSetId = AnyEntityUtil.findItemOneColumn(batch.getDomainId(), true, String.class, JobConfigSet.class, "id", "domainId,jobType,stageCd,defaultFlag", batch.getDomainId(), batch.getJobType(), batch.getStageCd(), true);
			batch.setJobConfigSetId(jobConfigSetId);
			
			// 5.2 IndConfigSet 설정
			String indConfigSetId = AnyEntityUtil.findItemOneColumn(batch.getDomainId(), true, String.class, IndConfigSet.class, "id", "domainId,jobType,stageCd,defaultFlag", batch.getDomainId(), batch.getJobType(), batch.getStageCd(), true);
			batch.setIndConfigSetId(indConfigSetId);
			
			// 5.3 작업 배치 업데이트
			this.queryManager.update(batch, "jobConfigSetId", "indConfigSetId");
		}
	}
	
	/**
	 * 메인 배치로 부터 랙 별 배치를 별도 생성한다. 
	 *  
	 * @param mainBatch
	 * @param newBatchId
	 * @param rack
	 * @param equipPreprocesses
	 * @return
	 */
	private JobBatch sliceBatch(JobBatch mainBatch, String newBatchId, Rack rack, List<OrderPreprocess> equipPreprocesses) {
		for(OrderPreprocess op : equipPreprocesses) {
			op.setBatchId(newBatchId);
		}
		
		int batchPcs = equipPreprocesses.stream().mapToInt(item -> item.getTotalPcs()).sum();
		
		if(ValueUtil.isEqualIgnoreCase(mainBatch.getId(), newBatchId)) {
			mainBatch.setStatus(JobBatch.STATUS_RUNNING);
			mainBatch.setEquipType(LogisConstants.EQUIP_TYPE_RACK);
			mainBatch.setEquipCd(rack.getRackCd());
			mainBatch.setEquipNm(rack.getRackNm());
			mainBatch.setInstructedAt(new Date());
			mainBatch.setBatchOrderQty(equipPreprocesses.size());
			mainBatch.setBatchPcs(batchPcs);
			this.queryManager.update(mainBatch, "status", "equipType", "equipCd", "equipNm", "instructedAt", "batchOrderQty", "batchPcs", "updatedAt");
			return mainBatch;
			
		} else {
			JobBatch newBatch = AnyValueUtil.populate(mainBatch, new JobBatch());
			newBatch.setId(newBatchId);
			newBatch.setEquipCd(rack.getRackCd());
			newBatch.setEquipNm(rack.getRackNm());
			newBatch.setBatchOrderQty(equipPreprocesses.size());
			newBatch.setBatchPcs(batchPcs);
			newBatch.setStatus(JobBatch.STATUS_RUNNING);
			newBatch.setInstructedAt(new Date());
			this.queryManager.insert(newBatch);
			return newBatch;
		}
	}
	
	/**
	 * 주문 정보를 주문 가공 정보를 토대로 업데이트
	 *
	 * @param batch
	 * @param sources
	 */
	private void updateOrdersBy(JobBatch batch, List<OrderPreprocess> sources) {
		Long domainId = batch.getDomainId();
		String mainBatchId = batch.getBatchGroupId();
		String newBatchId = batch.getId();

		OrderPreprocess first = sources.get(0);
		Map<String, Object> params = ValueUtil.newMap("domainId,mainBatchId,newBatchId,status,equipType,equipCd,equipNm,subEquipCd,currentDate", domainId, mainBatchId, newBatchId, Order.STATUS_WAIT, first.getEquipType(), first.getEquipCd(), first.getEquipNm(), first.getSubEquipCd(), new Date());
		String sql = "update orders set batch_id = :newBatchId, status = :status, equip_type = :equipType, equip_cd = :equipCd, equip_nm = :equipNm, sub_equip_cd = :subEquipCd, updated_at = :currentDate where domain_id = :domainId and batch_id = :mainBatchId and com_cd = :comCd and class_cd = :classCd";
		
		for(OrderPreprocess source : sources) {
			params.put("subEquipCd", source.getSubEquipCd());
			params.put("comCd", source.getComCd());
			params.put("classCd", source.getCellAssgnCd());
			this.queryManager.executeBySql(sql, params);
		}
	}
	
	/**
	 * 작업 데이터 생성
	 *
	 * @param batch
	 */
	private void generateJobInstancesBy(JobBatch batch) {
		Map<String, Object> params = ValueUtil.newMap("domainId,batchId", batch.getDomainId(), batch.getId());
		String insertQuery = this.dasQueryStore.getDasGenerateJobsByInstructionQuery();
		this.queryManager.executeBySql(insertQuery, params);
	}
	
	/**
	 * Work Cell 생성
	 *
	 * @param batch
	 * @param sources
	 */
	private void generateWorkCellBy(JobBatch batch, List<OrderPreprocess> sources) {
		// 1. 기존 WorkCell 삭제
		Long domainId = batch.getDomainId();
		List<String> subEquipCdList = AnyValueUtil.filterValueListBy(sources, "subEquipCd");
		Query condition = AnyOrmUtil.newConditionForExecution(domainId);
		condition.addFilter("cellCd", "in", subEquipCdList);
		this.queryManager.deleteList(WorkCell.class, condition);
		
		// 2. 새로 생성
		List<WorkCell> cellList = new ArrayList<WorkCell>(sources.size());
		for(OrderPreprocess source : sources) {
			WorkCell c = new WorkCell();
			c.setDomainId(domainId);
			c.setBatchId(batch.getId());
			c.setCellCd(source.getSubEquipCd());
			c.setComCd(source.getComCd());
			c.setJobType(batch.getJobType());
			c.setLastPickedQty(0);
			c.setClassCd(source.getCellAssgnCd());
			c.setActiveFlag(true);
			cellList.add(c);
		}
		
		// 3. WorkCell 리스트 생성
		AnyOrmUtil.insertBatch(cellList, 100);
	}

}
