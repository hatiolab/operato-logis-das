package operato.logis.das.service.impl;
     
import java.util.List;
import java.util.Map;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import operato.logis.das.query.store.RtnQueryStore;
import operato.logis.das.service.api.IDasIndicationService;
import operato.logis.das.service.util.RtnBatchJobConfigUtil;
import xyz.anythings.base.LogisCodeConstants;
import xyz.anythings.base.LogisConstants;
import xyz.anythings.base.entity.BoxPack;
import xyz.anythings.base.entity.JobBatch;
import xyz.anythings.base.entity.JobInstance;
import xyz.anythings.base.entity.Order;
import xyz.anythings.base.entity.Rack;
import xyz.anythings.base.entity.WorkCell;
import xyz.anythings.base.event.ICategorizeEvent;
import xyz.anythings.base.event.IClassifyErrorEvent;
import xyz.anythings.base.event.IClassifyInEvent;
import xyz.anythings.base.event.IClassifyOutEvent;
import xyz.anythings.base.event.IClassifyRunEvent;
import xyz.anythings.base.event.classfy.ClassifyErrorEvent;
import xyz.anythings.base.event.classfy.ClassifyRunEvent;
import xyz.anythings.base.model.Category;
import xyz.anythings.base.model.CategoryItem;
import xyz.anythings.base.service.api.IAssortService;
import xyz.anythings.base.service.api.IBoxingService;
import xyz.anythings.base.service.api.IIndicationService;
import xyz.anythings.base.service.api.IJobStatusService;
import xyz.anythings.base.service.impl.AbstractClassificationService;
import xyz.anythings.base.service.util.BatchJobConfigUtil;
import xyz.anythings.gw.entity.Gateway;
import xyz.anythings.gw.entity.Indicator;
import xyz.anythings.gw.event.GatewayInitEvent;
import xyz.anythings.sys.util.AnyEntityUtil;
import xyz.anythings.sys.util.AnyOrmUtil;
import xyz.anythings.sys.util.AnyValueUtil;
import xyz.elidom.dbist.dml.Query;
import xyz.elidom.exception.ElidomException;
import xyz.elidom.exception.server.ElidomRuntimeException;
import xyz.elidom.sys.SysConstants;
import xyz.elidom.sys.util.DateUtil;
import xyz.elidom.sys.util.MessageUtil;
import xyz.elidom.sys.util.ThrowUtil;
import xyz.elidom.util.ThreadUtil;
import xyz.elidom.util.ValueUtil; 

/**
 * 반품 분류 서비스 구현
 *
 * @author shortstop
 */
@Component("rtnAssortService")
public class RtnAssortService extends AbstractClassificationService implements IAssortService {

	/**
	 * 박스 서비스
	 */
	@Autowired
	private RtnBoxingService boxService;
	/**
	 * RTN 쿼리 스토어
	 */
	@Autowired
	private RtnQueryStore rtnQueryStore;
	
	@Override
	public String getJobType() {
		return LogisConstants.JOB_TYPE_RTN;
	}

	@Override
	public IBoxingService getBoxingService(Object... params) {
		return this.boxService;
	}
	
	@Override
	public Object boxCellMapping(JobBatch batch, String cellCd, String boxId) {
		return this.boxService.assignBoxToCell(batch, cellCd, boxId);
	}
	
	@EventListener(classes = GatewayInitEvent.class, condition = "#gwInitEvent.eventStep == 2")
	public void handleGatewayInitReport(GatewayInitEvent gwInitEvent) {
		// Gateway 정보 추출
		Gateway gateway = gwInitEvent.getGateway();
		
		if(gateway != null) {
			// 1. Gateway 정보로 호기 리스트 추출
			Long domainId = gwInitEvent.getDomainId();
			String sql = "select rack_cd, batch_id from racks where domain_id = :domainId and job_type = :jobType and rack_cd in (select distinct(equip_cd) as equip_cd from cells where domain_id = :domainId and ind_cd in (select ind_cd from indicators where domain_id = :domainId and gw_cd = :gwCd) order by equip_cd)";
			List<Rack> rackList = this.queryManager.selectListBySql(sql, ValueUtil.newMap("domainId,jobType,gwCd", domainId, LogisConstants.JOB_TYPE_RTN, gateway.getGwCd()), Rack.class, 0, 0);
			
			// 2. 호기로 부터 현재 작업 중인 배치 추출 
			for(Rack rack : rackList) {
				// 2-1. 호기 체크
				if(ValueUtil.isEmpty(rack.getBatchId())) {
					continue;
				}
				
				// 2-2. 작업 배치 및 상태 체크
				JobBatch batch = AnyEntityUtil.findEntityById(false, JobBatch.class, rack.getBatchId());
				
				if(batch == null || ValueUtil.isNotEqual(batch.getStatus(), JobBatch.STATUS_RUNNING)) {
					continue;
				}
				
				// 2-3. 호기 코드, 게이트웨이 코드로 표시기 이전 상태 복원
				this.restoreMpiOn(batch, gateway, rack.getRackCd());
			}
		}
	}
	
	/**
	 * 작업 배치, 게이트웨이, 호기별로 이전 작업 리스트 표시기 점등
	 * 
	 * @param batch
	 * @param gw
	 * @param rackCd
	 */
	public void restoreMpiOn(JobBatch batch, Gateway gw, String rackCd) {
		if(ValueUtil.isEqual(batch.getStatus(), JobBatch.STATUS_RUNNING)) {
			// 1. 해당 호기의 모든 작업 존 조회
			Long domainId = batch.getDomainId();
			String sql = "select distinct station_cd from cells where domain_id = :domainId and equip_cd = :equipCd order by station_cd";
			List<String> stationList = this.queryManager.selectListBySql(sql, ValueUtil.newMap("domainId,equipCd", domainId, rackCd), String.class, 0, 0);
			IJobStatusService jobStatusSvc = this.serviceDispatcher.getJobStatusService(batch);
			IIndicationService indSvc = this.serviceDispatcher.getIndicationService(batch);
			sql = this.rtnQueryStore.getRtnFindStationWorkingInputSeq();
			Map<String, Object> params = ValueUtil.newMap("domainId,batchId,jobStatus,status", domainId, batch.getId(), LogisConstants.JOB_STATUS_PICKING, LogisConstants.JOB_STATUS_PICKING);
			
			// 2. 추출한 작업 존별로 가장 작은 투입 순서에 피킹 중인 작업 리스트 조회
			for(String stationCd : stationList) {
				params.put("stationCd", stationCd);
				Integer inputSeq = this.queryManager.selectBySql(sql, params, Integer.class);
				
				if(inputSeq != null && inputSeq > 0) {
					// 3. 배치, 작업 존, 투입 순서, 상태로 작업 리스트 조회
					params.put("inputSeq", inputSeq);
					List<JobInstance> jobList = jobStatusSvc.searchPickingJobList(batch, params);
					
					// 4. 추출한 작업 리스트에 대해서 표시기 점등
					indSvc.indicatorsOn(batch, true, jobList);
					// TODO 게이트웨이 소속 로케이션에 대해서 작업이 존재하고 작업이 모두 완료된 경우 FULLBOX, END 표시를 한다.
				}
				
				ThreadUtil.sleep(100);
			}
		}
	}
	
	@Override
	public void batchStartAction(JobBatch batch) {
		// 설정에서 작업배치 시에 게이트웨이 리부팅 할 지 여부 조회
		boolean gwReboot = RtnBatchJobConfigUtil.isGwRebootWhenInstruction(batch);
		
		if(gwReboot) {
			IIndicationService indSvc = this.serviceDispatcher.getIndicationService(batch);
			List<Gateway> gwList = indSvc.searchGateways(batch);
			
			// 게이트웨이 리부팅 처리
			for(Gateway gw : gwList) {
				indSvc.rebootGateway(batch, gw);
			}
		}
		
		// 설정에서 작업 지시 시점에 박스 매핑 표시 여부 조회
		if(RtnBatchJobConfigUtil.isIndOnAssignedCellWhenInstruction(batch)) {
			// 게이트웨이 리부팅 시에는 리부팅 프로세스 완료시까지 약 1분여간 기다린다.
			if(gwReboot) {
				int sleepTime = RtnBatchJobConfigUtil.getWaitDuarionIndOnAssignedCellWhenInstruction(batch);
				if(sleepTime > 0) {
					ThreadUtil.sleep(sleepTime * 1000);
				}
			}
			
			// 표시기에 박스 매핑 표시 
			((IDasIndicationService)this.serviceDispatcher.getIndicationService(batch)).displayAllForBoxMapping(batch);
		}
	}

	@Override
	public void batchCloseAction(JobBatch batch) {
		// 모든 셀에 남아 있는 잔량에 대해 풀 박싱 여부 조회
		if(RtnBatchJobConfigUtil.isBatchFullboxWhenClosingEnabled(batch)) {
			// 배치 풀 박싱
			this.boxService.batchBoxing(batch);
		}
	}

	@Override
	@EventListener(classes = ICategorizeEvent.class, condition = "#event.jobType == 'RTN'")
	public Category categorize(ICategorizeEvent event) {
		String comCd = event.getComCd();
		String skuCd = event.getInputCode();
		List<String> batchIdList = event.getBatchIdList();
		
		Category category = new Category();
		category.setSkuCd(event.getInputCode());
		String sql = "select equip_type, equip_cd, equip_nm, sku_cd, sum(order_qty) as order_qty, sum(picked_qty) as picked_qty from orders where domain_id = :domainId and com_cd = :comCd and sku_cd = :skuCd and batch_id in (:batchIdList) group by equip_type, equip_cd, equip_nm, sku_cd";
		Map<String, Object> params = ValueUtil.newMap("domainId,comCd,skuCd,batchIdList", event.getDomainId(), comCd, skuCd, batchIdList);
		List<CategoryItem> items = this.queryManager.selectListBySql(sql, params, CategoryItem.class, 0, 0);
		category.setItems(items);
		event.setResult(category);
		event.setExecuted(true);
		return category;
	}

	@Override
	public String checkInput(JobBatch batch, String inputId, Object... params) {
		// inputId를 체크하여 어떤 코드 인지 (상품 코드, 상품 바코드, 박스 ID, 랙 코드, 셀 코드 등)를 찾아서 리턴 
		if(BatchJobConfigUtil.isBoxIdValid(batch, inputId)) {
			return LogisCodeConstants.INPUT_TYPE_BOX_ID;
			
		} else if(BatchJobConfigUtil.isSkuCdValid(batch, inputId)) {
			return LogisCodeConstants.INPUT_TYPE_SKU_CD;
		
		} else if(BatchJobConfigUtil.isRackCdValid(batch, inputId)) {
			return LogisCodeConstants.INPUT_TYPE_RACK_CD;
			
		} else if(BatchJobConfigUtil.isCellCdValid(batch, inputId)) {
			return LogisCodeConstants.INPUT_TYPE_CELL_CD;
			
		} else if(BatchJobConfigUtil.isIndCdValid(batch, inputId)) {
			return LogisCodeConstants.INPUT_TYPE_IND_CD;
			
		} else {
			String msg = MessageUtil.getMessage("CANT_DISTINGUISH_WHAT_INPUT_TYPE", "Can't distinguish what type of input the scanned information is.");
			throw new ElidomRuntimeException(msg);
		}
	}

	@Override
	public Object input(IClassifyInEvent inputEvent) { 
		return this.inputSkuSingle(inputEvent); 
		
	} 
	
	@EventListener(classes = ClassifyRunEvent.class, condition = "#exeEvent.jobType == 'RTN'")
	public Object classify(IClassifyRunEvent exeEvent) { 
		String classifyAction = exeEvent.getClassifyAction();
		JobInstance job = exeEvent.getJobInstance();
		
		try {
			switch(classifyAction) {
				// 확정 처리
				case LogisCodeConstants.CLASSIFICATION_ACTION_CONFIRM :
					this.confirmAssort(exeEvent);
					break;
					
				// 작업 취소
				case LogisCodeConstants.CLASSIFICATION_ACTION_CANCEL :
					this.cancelAssort(exeEvent);
					break;
					
				// 수량 조정 처리  
				case LogisCodeConstants.CLASSIFICATION_ACTION_MODIFY :
					this.splitAssort(exeEvent);
					break;
					
				// 풀 박스
				case LogisCodeConstants.CLASSIFICATION_ACTION_FULL :
					if(exeEvent instanceof IClassifyOutEvent) {
						this.fullBoxing((IClassifyOutEvent)exeEvent);
					}
					break;
			}
		} catch (Throwable th) {
			IClassifyErrorEvent errorEvent = new ClassifyErrorEvent(exeEvent, exeEvent.getEventStep(), th);
			this.handleClassifyException(errorEvent);
			return exeEvent;
		}
		
		exeEvent.setExecuted(true);
		return job;
	}
	 
	@Override
	public Object output(IClassifyOutEvent outputEvent) {
		return this.fullBoxing(outputEvent);
	}

	@Override
	public Object inputSkuSingle(IClassifyInEvent inputEvent) {
		JobBatch batch = inputEvent.getJobBatch(); 
		String comCd = inputEvent.getComCd();
		String classCd = inputEvent.getInputCode();
		Integer inputQty = inputEvent.getInputQty();
		
		// 1. 투입된 상품 코드를 제외하고 투입 이후 확정 처리가 안 된 상품을 조회 
		Query condition = AnyOrmUtil.newConditionForExecution(batch.getDomainId());
		condition.addFilter("batchId", batch.getId());
		condition.addFilter("comCd", comCd);
		// 설정에서 셀 - 박스와 매핑될 타겟 필드를 조회  
		String classFieldName = RtnBatchJobConfigUtil.getBoxMappingTargetField(batch);
		condition.addFilter(classFieldName, SysConstants.NOT_EQUAL, classCd);
		condition.addFilter("equipCd", batch.getEquipCd());
		condition.addFilter("status", LogisConstants.JOB_STATUS_PICKING);
		condition.addFilter("pickingQty", SysConstants.GREATER_THAN_EQUAL, 1);
		
		if(this.queryManager.selectSize(JobInstance.class, condition) > 0) { 
			// 투입한 후 완료 처리를 안 한 작업이 있습니다
			throw ThrowUtil.newValidationErrorWithNoLog(true, "NOT_BEEN_COMPLETED_AFTER_INPUT");
		}
		
		// 2. 작업 인스턴스 조회 
		List<JobInstance> jobList = this.serviceDispatcher.getJobStatusService(batch).searchPickingJobList(batch, null, classCd);
		if(ValueUtil.isEmpty(jobList)) {
			// 투입한 상품으로 처리할 작업이 없습니다
			throw ThrowUtil.newValidationErrorWithNoLog(true, "NO_JOBS_TO_PROCESS_BY_INPUT");
		}
		
		JobInstance job = jobList.get(0);
		Integer pickingQty = job.getPickingQty() + inputQty;
		String nowStr = DateUtil.currentTimeStr();
		
		// 3. 작업 인스턴스 정보 업데이트
		if(job.getPickQty() >= (job.getPickedQty() + pickingQty)) {
			// 3-1. 다음 InputSeq 조회
			int nextInputSeq = this.serviceDispatcher.getJobStatusService(batch).findNextInputSeq(batch);
			job.setInputSeq(nextInputSeq);
			job.setPickingQty(pickingQty);
			job.setStatus(LogisConstants.JOB_STATUS_PICKING);
			
			if(ValueUtil.isEmpty(job.getPickStartedAt())) {
				job.setPickStartedAt(nowStr);
			}
			
			if(ValueUtil.isEmpty(job.getInputAt())) {
				job.setInputAt(nowStr);
			} 
			
			job.setColorCd(LogisConstants.COLOR_RED);
			this.queryManager.update(job, "inputSeq", "pickingQty", "status", "pickStartedAt", "inputAt", "colorCd", "updatedAt");
			
			// 3-2 표시기 점등
			this.serviceDispatcher.getIndicationService(batch).indicatorOnForPick(job, 0, job.getPickingQty(), 0);
			
		} else {
			// 작업 오더의 계획 수량을 초과했습니다.
			throw ThrowUtil.newValidationErrorWithNoLog(true, "WORK_ORDER_OVER_QTY");
		} 
		 
		return job;
	}

	@Override
	public Object inputSkuBundle(IClassifyInEvent inputEvent) {
		// 묶음 투입은 지원하지 않습니다.
		throw ThrowUtil.newValidationErrorWithNoLog(true, "NOT_SUPPORTED_METHOD");
	}

	@Override
	public Object inputSkuBox(IClassifyInEvent inputEvent) {
		// 박스 단위 투입은 지원하지 않습니다.
		throw ThrowUtil.newValidationErrorWithNoLog(true, "NOT_SUPPORTED_METHOD");
	}

	@Override
	public Object inputForInspection(IClassifyInEvent inputEvent) {
		// 검수를 위한 투입은 지원하지 않습니다.
		throw ThrowUtil.newValidationErrorWithNoLog(true, "NOT_SUPPORTED_METHOD");
	}

	@Override
	public void confirmAssort(IClassifyRunEvent exeEvent) {
		// 1. 이벤트에서 작업 데이터 추출
		JobInstance job = exeEvent.getJobInstance();
		
		// 2. 확정 처리 
		if(job.isTodoJob() && job.getPickedQty() < job.getPickQty()) {
			// 2.1 작업 확정 처리 
			int resQty = job.getPickingQty();
			job.setPickedQty(job.getPickedQty() + resQty);
			job.setPickingQty(0);
			String status = (job.getPickedQty() >= job.getPickQty()) ?  LogisConstants.JOB_STATUS_FINISH : LogisConstants.JOB_STATUS_PICKING;
			job.setStatus(status);
			this.queryManager.update(job, "pickingQty", "pickedQty", "status", "updatedAt");
			
			// 2.2 주문 정보 업데이트 처리
			this.updateOrderPickedQtyByConfirm(job, resQty);			
		}
		
		// 3. 다음 작업 처리
		this.doNextJob(job, exeEvent.getWorkCell(), this.checkCellAssortEnd(job, false));
	}

	@Override
	public void cancelAssort(IClassifyRunEvent exeEvent) {
		// 1. 이벤트에서 작업 데이터 추출
		JobInstance job = exeEvent.getJobInstance();
		
		// 2. 이미 작업 취소 상태이면 스킵
		if(ValueUtil.isEqualIgnoreCase(LogisConstants.JOB_STATUS_CANCEL, job.getStatus()) || job.getPickingQty() == 0) {
			return;
		}
		
		// 3. 작업 취소 처리 
		if(job.isTodoJob()) {
			job.setPickingQty(0);
			this.queryManager.update(job, "pickingQty", "updatedAt");			
		}
			
		// 4. 다음 작업 처리
		this.doNextJob(job, exeEvent.getWorkCell(), this.checkCellAssortEnd(job, false));
	}

	@Override
	public int splitAssort(IClassifyRunEvent exeEvent) { 
		// 1. 이벤트에서 작업 데이터 추출
		JobInstance job = exeEvent.getJobInstance();
		int resQty = exeEvent.getResQty();
		
		// 2. 수량 조절 처리
		if(resQty > 0 && job.isDoneJob()) {
			// 2.1 작업 수량 조절 처리
			int pickedQty = job.getPickedQty() + resQty;
			job.setPickingQty(0);
			job.setPickedQty(pickedQty);
			if(job.getPickedQty() >= job.getPickQty()) { 
				job.setStatus(LogisConstants.JOB_STATUS_FINISH);
			}
			this.queryManager.update(job, "pickingQty", "pickedQty", "status", "updatedAt");
			
			// 2.2. 주문 정보 업데이트 처리
			this.updateOrderPickedQtyByConfirm(job, resQty);			
		}
				
		// 3. 다음 작업 처리
		this.doNextJob(job, exeEvent.getWorkCell(), this.checkCellAssortEnd(job, false));
		
		// 4. 조정 수량 리턴 
		return resQty;
	}

	@Override
	public int undoAssort(IClassifyRunEvent exeEvent) {
		// 1. 작업 데이터 확정 수량 0으로 업데이트 
		JobInstance job = exeEvent.getJobInstance();
		int pickedQty = job.getPickedQty();
		job.setPickingQty(0);
		job.setPickedQty(0);
		this.queryManager.update(job, "pickingQty", "pickedQty", "updatedAt");
		
		// 2. 주문 데이터 확정 수량 마이너스 처리
		Query condition = AnyOrmUtil.newConditionForExecution(job.getDomainId(), 1, 1);
		condition.addFilter("batchId", job.getId());
		condition.addFilter("equipCd", job.getEquipCd());
		// 설정에서 셀 - 박스와 매핑될 타겟 필드를 조회  
		String classFieldName = RtnBatchJobConfigUtil.getBoxMappingTargetField(exeEvent.getJobBatch());
		condition.addFilter(classFieldName, job.getClassCd());
		condition.addFilter("status", "in", ValueUtil.toList(Order.STATUS_RUNNING, Order.STATUS_FINISHED));
		condition.addFilter("pickingQty", ">=", pickedQty);
		condition.addOrder("updatedAt", false);
		
		List<Order> orderList = this.queryManager.selectList(Order.class, condition);
		if(ValueUtil.isNotEmpty(orderList)) {
			Order order = orderList.get(0);
			order.setPickedQty(order.getPickedQty() - pickedQty);
			order.setStatus(Order.STATUS_RUNNING);
			this.queryManager.update(order, "pickedQty", "status", "updatedAt");
		}
		
		// 3. 다음 작업 처리
		this.doNextJob(job, exeEvent.getWorkCell(), this.checkCellAssortEnd(job, false));
		
		// 4. 주문 취소된 확정 수량 리턴
		return pickedQty;
	}

	@Override
	public BoxPack fullBoxing(IClassifyOutEvent outEvent) {

		// 1. 작업 데이터 추출
		JobInstance job = outEvent.getJobInstance();
		
		// 2. 풀 박스 체크
		if(ValueUtil.isEqualIgnoreCase(LogisConstants.JOB_STATUS_BOXED, job.getStatus())) {
			// 이미 처리된 항목입니다. --> "작업[" + job.getId() + "]은 이미 풀 박스가 완료되었습니다."
			String msg = MessageUtil.getMessage("ALREADY_BEEN_PROCEEDED", "Already been proceeded.");
			throw new ElidomRuntimeException(msg);
		}
		
		// 3. 풀 박스 처리 
		BoxPack boxPack = this.boxService.fullBoxing(outEvent.getJobBatch(), outEvent.getWorkCell(), ValueUtil.toList(job), this);
		
		// 4. 다음 작업 처리
		if(boxPack != null) {
			this.doNextJob(job, outEvent.getWorkCell(), this.checkCellAssortEnd(job, false));
		}
		
		// 5. 박스 리턴
		return boxPack;
	}

	@Override
	public BoxPack partialFullboxing(IClassifyOutEvent outEvent) {
		// 1. 작업 데이터 추출
		JobInstance job = outEvent.getJobInstance();
		
		// 2. 풀 박스 체크
		if(ValueUtil.isEqualIgnoreCase(LogisConstants.JOB_STATUS_BOXED, job.getStatus())) {
			// 이미 처리된 항목입니다. --> "작업[" + job.getId() + "]은 이미 풀 박스가 완료되었습니다."
			String msg = MessageUtil.getMessage("ALREADY_BEEN_PROCEEDED", "Already been proceeded.");
			throw new ElidomRuntimeException(msg);
		}
		
		// 3. 풀 박스 처리
		int resQty = outEvent.getReqQty();
		BoxPack boxPack = this.boxService.partialFullboxing(outEvent.getJobBatch(), outEvent.getWorkCell(), ValueUtil.toList(job), resQty, this);
		
		// 4. 다음 작업 처리
		if(boxPack != null) {
			this.doNextJob(job, outEvent.getWorkCell(), this.checkCellAssortEnd(job, false));
		}
		
		// 5. 박스 리턴
		return boxPack;
	}

	@Override
	public BoxPack cancelBoxing(Long domainId, BoxPack box) {
		// 1. 풀 박스 취소 전 처리
		if(box == null) {
			// 셀에 박싱 처리할 작업이 없습니다 --> 박싱 취소할 박스가 없습니다.
			throw ThrowUtil.newValidationErrorWithNoLog(true, "NO_JOBS_FOR_BOXING");
		}
		
		// 2. 풀 박스 취소 
		BoxPack boxPack = this.boxService.cancelFullboxing(box);
		
		// 3. 다음 작업 처리
		JobInstance job = this.findLatestJobForBoxing(box.getDomainId(), box.getBatchId(), box.getSubEquipCd());
		WorkCell cell = AnyEntityUtil.findEntityBy(domainId, true, WorkCell.class, "domainId,batchId,cellCd", box.getDomainId(), box.getBatchId(), box.getSubEquipCd());
		this.doNextJob(job, cell, this.checkCellAssortEnd(job, false));
		
		// 4. 박스 리턴
		return boxPack;
	}

	@Override
	public JobInstance splitJob(JobInstance job, WorkCell workCell, int splitQty) {
		// 1. 작업 분할이 가능한 지 체크
		if(job.getPickQty() - splitQty < 0) {
			String msg = MessageUtil.getMessage("SPLIT_QTY_LARGER_THAN_PLANNED_QTY", "예정수량보다 분할수량이 커서 작업분할 처리를 할 수 없습니다");
			throw new ElidomRuntimeException(msg);
		}
		
		// 2. 기존 작업 데이터 복사 
		JobInstance boxedJob = AnyValueUtil.populate(job, new JobInstance());
		String nowStr = DateUtil.currentTimeStr();
		int pickedQty = job.getPickedQty() - splitQty;
		
		// 3. 새 작업 데이터의 수량 및 상태 변경
		boxedJob.setId(AnyValueUtil.newUuid36());
		boxedJob.setPickQty(splitQty);
		boxedJob.setPickingQty(0);
		boxedJob.setPickedQty(splitQty);
		boxedJob.setBoxedAt(nowStr);
		boxedJob.setPickEndedAt(nowStr);
		boxedJob.setStatus(LogisConstants.JOB_STATUS_BOXED);
		this.queryManager.insert(boxedJob);
		 
		// 4. 기존 작업 데이터의 수량 및 상태 변경 
		job.setPickQty(job.getPickQty() - splitQty);
		job.setPickedQty(pickedQty);
		job.setStatus(pickedQty > 0 ? LogisConstants.JOB_STATUS_PICKING : LogisConstants.JOB_STATUS_WAIT);
		this.queryManager.update(job, "pickQty", "pickedQty", "status", "updatedAt");	
		
		// 5. 기존 작업 데이터 리턴
		return job;
	}
	
	/**
	 * 다음 작업 처리
	 * 
	 * @param job
	 * @param cell
	 * @param cellEndFlag
	 */
	protected void doNextJob(JobInstance job, WorkCell cell, boolean cellEndFlag) {
		// 1. 해당 로케이션의 작업이 모두 완료 상태인지 체크 
		if(cellEndFlag) {
			this.finishAssortCell(job, cell, cellEndFlag);
			
		// 2. 현재 로케이션에 존재하는 '피킹 시작' 상태 작업의 '피킹 중 수량' 정보를 조회하여 표시기 재점등
		} else {
			if(job.getPickingQty() > 0) {
				this.serviceDispatcher.getIndicationService(job).indicatorOnForPick(job, 0, job.getPickingQty(), 0);
			}
		}
	}
	
	@Override
	public JobInstance findLatestJobForBoxing(Long domainId, String batchId, String cellCd) {
		// 박싱 처리를 위해 로케이션에 존재하는 박스 처리할 작업을 조회
		String sql = "select * from (select * from job_instances where domain_id = :domainId and batch_id = :batchId and sub_equip_cd = :cellCd and status in (:statuses) order by pick_ended_at desc) where rownum <= 1";
		Map<String, Object> params = ValueUtil.newMap("domainId,batchId,cellCd,statuses", domainId, batchId, cellCd, LogisConstants.JOB_STATUS_PF);
		return this.queryManager.selectBySql(sql, params, JobInstance.class);
	}

	@Override
	public boolean checkCellAssortEnd(JobInstance job, boolean finalEndCheck) {
		Query condition = AnyOrmUtil.newConditionForExecution(job.getDomainId());
		condition.addFilter("batchId", job.getBatchId());
		condition.addFilter("subEquipCd", job.getSubEquipCd());
		List<String> statuses = finalEndCheck ? LogisConstants.JOB_STATUS_WIPFC : LogisConstants.JOB_STATUS_WIPC;
		condition.addFilter("status", SysConstants.IN, statuses);
		return this.queryManager.selectSize(JobInstance.class, condition) == 0;
	}

	@Override
	public boolean finishAssortCell(JobInstance job, WorkCell workCell, boolean finalEndFlag) {
	    // 1. 로케이션 분류 최종 완료 상태인지 즉 더 이상 박싱 처리할 작업이 없는지 체크 
		boolean finalEnded = this.checkCellAssortEnd(job, finalEndFlag);
	    
		// 2. 로케이션에 완료 상태 기록
		String cellJobStatus = finalEnded ? LogisConstants.CELL_JOB_STATUS_ENDED : LogisConstants.CELL_JOB_STATUS_ENDING;
		workCell.setStatus(cellJobStatus);
		if(!finalEnded) { 
			workCell.setJobInstanceId(job.getId()); 
		}
		this.queryManager.update(workCell, "status", "jobInstanceId", "updatedAt");
		
		// 3. 표시기에 분류 처리 내용 표시
		this.serviceDispatcher.getIndicationService(job).indicatorOnForPickEnd(job, finalEnded);
		return true;
	}
	
	@Override
	public boolean checkEndClassifyAll(JobBatch batch) {
		Query condition = AnyOrmUtil.newConditionForExecution(batch.getDomainId());
		condition.addFilter("batchId", batch.getId());
		condition.addFilter("statuses", SysConstants.IN, LogisConstants.JOB_STATUS_WIPC);
		return this.queryManager.selectSize(JobInstance.class, condition) == 0;
	}
	
	@Override
	public void handleClassifyException(IClassifyErrorEvent errorEvent) {
		// 1. 예외 정보 추출 
		Throwable th = errorEvent.getException();
		// 2. 디바이스 정보 추출
		String device = errorEvent.getClassifyRunEvent().getClassifyDevice();
		// 3. 표시기로 부터 온 요청이 에러인지 체크
		boolean isIndicatorDevice = !ValueUtil.isEqualIgnoreCase(device, Indicator.class.getSimpleName());

		// 4. 모바일 알람 이벤트 전송
		if(th != null) {
			String cellCd = (errorEvent.getWorkCell() != null) ? errorEvent.getWorkCell().getCellCd() : (errorEvent.getJobInstance() != null ? errorEvent.getJobInstance().getSubEquipCd() : null);
			String stationCd = ValueUtil.isNotEmpty(cellCd) ? 
				AnyEntityUtil.findEntityBy(errorEvent.getDomainId(), false, String.class, "stationCd", "domainId,cellCd", errorEvent.getDomainId(), cellCd) : null;
			
			String errMsg = (th.getCause() == null) ? th.getMessage() : th.getCause().getMessage();
			this.sendMessageToMobileDevice(errorEvent.getJobBatch(), isIndicatorDevice ? null : device, stationCd, "error", errMsg);			
		}

		// 5. 예외 발생
		throw (th instanceof ElidomException) ? (ElidomException)th : new ElidomRuntimeException(th);
	}
	
	@Override
	public boolean checkStationJobsEnd(JobInstance job, String stationCd) {
		// 반품에서는 사용 안 함 
		return false;
	}
	
	/**
	 * 분류 확정 처리시에 작업 정보에 매핑되는 주문 정보를 찾아서 확정 수량 업데이트 
	 *
	 * @param job
	 * @param totalPickedQty
	 */
	private void updateOrderPickedQtyByConfirm(JobInstance job, int totalPickedQty) {
		// 1. 주문 정보 조회		
		Query condition = AnyOrmUtil.newConditionForExecution(job.getDomainId());
		condition.addFilter("batchId",	job.getBatchId()); 
		condition.addFilter("skuCd",	job.getSkuCd());
		condition.addFilter("status",	SysConstants.IN,	ValueUtil.toList(LogisConstants.COMMON_STATUS_RUNNING, LogisConstants.COMMON_STATUS_WAIT));
		condition.addOrder("orderNo",	true);
		condition.addOrder("pickedQty",	false);
		List<Order> sources = this.queryManager.selectList(Order.class, condition);   
 		
		// 2. 주문에 피킹 확정 수량 업데이트
		for(Order source : sources) {
			if(totalPickedQty > 0) {
				int orderQty = source.getOrderQty();
				int pickedQty = source.getPickedQty();
				int remainQty = orderQty - pickedQty;
				
				// 2-1. 주문 처리 수량 업데이트 및 주문 라인 분류 종료
				if(totalPickedQty >= remainQty) {
					source.setPickedQty(source.getPickedQty() + remainQty);
					source.setStatus(LogisConstants.COMMON_STATUS_FINISHED);  
					totalPickedQty = totalPickedQty - remainQty;
					
				// 2-2. 주문 처리 수량 업데이트
				} else if(remainQty > totalPickedQty) {
					source.setPickedQty(source.getPickedQty() + totalPickedQty);
					totalPickedQty = 0; 
				} 
				
				this.queryManager.update(source, "pickedQty", "status", "updatedAt");
				
			} else {
				break;
			}			
		}
	}
	
	/**
	 * 모바일 디바이스에 메시지 전송
	 * 
	 * @param batch
	 * @param toDevice
	 * @param stationCd
	 * @param notiType
	 * @param message
	 */
	private void sendMessageToMobileDevice(JobBatch batch, String toDevice, String stationCd, String notiType, String message) {
		String[] deviceList = null;
		
		if(toDevice == null) {
			// toDevice가 없다면 사용 디바이스 리스트 조회
			deviceList = RtnBatchJobConfigUtil.getDeviceList(batch) == null ? null : RtnBatchJobConfigUtil.getDeviceList(batch);
		} else {
			deviceList = new String[] { toDevice };
		}
		
		if(deviceList != null) {
			for(String device : deviceList) {
				this.serviceDispatcher.getDeviceService().sendMessageToDevice(batch.getDomainId(), device, batch.getStageCd(), batch.getEquipType(), batch.getEquipCd(), stationCd, null, batch.getJobType(), notiType, message, null);
			}
		}
	}

}
