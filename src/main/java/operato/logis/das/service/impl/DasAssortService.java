package operato.logis.das.service.impl;

import java.util.List;
import java.util.Map;
import java.util.StringJoiner;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import operato.logis.das.query.store.DasQueryStore;
import operato.logis.das.service.api.IDasIndicationService;
import operato.logis.das.service.util.DasBatchJobConfigUtil;
import xyz.anythings.base.LogisCodeConstants;
import xyz.anythings.base.LogisConstants;
import xyz.anythings.base.entity.BoxPack;
import xyz.anythings.base.entity.JobBatch;
import xyz.anythings.base.entity.JobInput;
import xyz.anythings.base.entity.JobInstance;
import xyz.anythings.base.entity.Order;
import xyz.anythings.base.entity.SKU;
import xyz.anythings.base.entity.WorkCell;
import xyz.anythings.base.event.ICategorizeEvent;
import xyz.anythings.base.event.IClassifyErrorEvent;
import xyz.anythings.base.event.IClassifyInEvent;
import xyz.anythings.base.event.IClassifyOutEvent;
import xyz.anythings.base.event.IClassifyRunEvent;
import xyz.anythings.base.event.classfy.ClassifyErrorEvent;
import xyz.anythings.base.event.classfy.ClassifyRunEvent;
import xyz.anythings.base.event.input.InputEvent;
import xyz.anythings.base.model.Category;
import xyz.anythings.base.model.CategoryItem;
import xyz.anythings.base.service.api.IAssortService;
import xyz.anythings.base.service.api.IBoxingService;
import xyz.anythings.base.service.api.IIndicationService;
import xyz.anythings.base.service.impl.AbstractClassificationService;
import xyz.anythings.base.service.util.BatchJobConfigUtil;
import xyz.anythings.gw.GwConstants;
import xyz.anythings.gw.entity.Gateway;
import xyz.anythings.gw.entity.Indicator;
import xyz.anythings.gw.service.mq.model.device.DeviceCommand;
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
 * 출고용 분류 서비스 구현
 *
 * @author shortstop
 */
@Component("dasAssortService")
public class DasAssortService extends AbstractClassificationService implements IAssortService {

	/**
	 * 박스 서비스
	 */
	@Autowired
	private DasBoxingService boxService;
	/**
	 * DAS 쿼리 스토어
	 */
	@Autowired
	private DasQueryStore dasQueryStore;
	
	@Override
	public String getJobType() {
		return LogisConstants.JOB_TYPE_DAS;
	}

	@Override
	public IBoxingService getBoxingService(Object... params) {
		return this.boxService;
	}
	
	@Override
	public Object boxCellMapping(JobBatch batch, String cellCd, String boxId) {
		return this.boxService.assignBoxToCell(batch, cellCd, boxId);
	}
	
	@Override
	public void batchStartAction(JobBatch batch) {
		// 설정에서 작업배치 시에 게이트웨이 리부팅 할 지 여부 조회
		boolean gwReboot = DasBatchJobConfigUtil.isGwRebootWhenInstruction(batch);
		
		if(gwReboot) {
			IIndicationService indSvc = this.serviceDispatcher.getIndicationService(batch);
			List<Gateway> gwList = indSvc.searchGateways(batch);
			
			// 게이트웨이 리부팅 처리
			for(Gateway gw : gwList) {
				indSvc.rebootGateway(batch, gw);
			}
		}
		
		// 설정에서 작업 지시 시점에 박스 매핑 표시 여부 조회 		
		if(DasBatchJobConfigUtil.isIndOnAssignedCellWhenInstruction(batch)) {
			// 게이트웨이 리부팅 시에는 리부팅 프로세스 완료시까지 약 1분여간 기다린다.
			if(gwReboot) {
				int sleepTime = DasBatchJobConfigUtil.getWaitDuarionIndOnAssignedCellWhenInstruction(batch);
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
		if(DasBatchJobConfigUtil.isBatchFullboxWhenClosingEnabled(batch)) {
			// 배치 풀 박싱
			this.boxService.batchBoxing(batch);
		}
	}

	@Override
	public Category categorize(ICategorizeEvent event) {
		// 1. 배치 추출
		Long domainId = event.getDomainId();
		JobBatch batch = event.getJobBatch();
		
		// 2. 상품 체크를 위한 조회
		SKU sku = AnyEntityUtil.findEntityBy(domainId, true, SKU.class, null, "domainId,comCd,skuCd", domainId, batch.getComCd(), event.getInputCode());
		
		// 3. 중분류 정보 조회
		String batchGroupId = event.getBatchGroupId();
		Map<String, Object> params = ValueUtil.newMap("domainId,comCd,skuCd,jobType", domainId, sku.getComCd(), sku.getSkuCd(), batch.getJobType());
		params.put(ValueUtil.isEmpty(batchGroupId) ? "batchStatus" : "batchGroupId", ValueUtil.isEmpty(batchGroupId) ? JobBatch.STATUS_RUNNING : batchGroupId);
		
		// 4. 중분류 수량을 분류 처리한 수량에서 제외할 건지 여부 설정		
		boolean fixedQtyMode = DasBatchJobConfigUtil.isCategorizationDisplayFixedQtyMode(batch);		
		//    호기를 좌측 정렬할 지 우측 정렬할 지 여부 설정
		boolean regSortAsc = DasBatchJobConfigUtil.isCategorizationRackSortMode(batch);
		params.put(fixedQtyMode ? "qtyFix" : "qtyFilter", true);
		params.put(regSortAsc ? "rackAsc" : "rackDesc", true);
		
		// 5. 중분류 쿼리 조회
		String sql = this.dasQueryStore.getDasCategorizationQuery();
		List<CategoryItem> items = this.queryManager.selectListBySql(sql, params, CategoryItem.class, 0, 0);
		
		// 6. 최종 호기 순 (혹은 역순)으로 중분류 정보 재배치
		return new Category(batchGroupId, sku, items);
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
			// 스캔한 정보가 어떤 투입 유형인지 구분할 수 없습니다.
			String msg = MessageUtil.getMessage("CANT_DISTINGUISH_WHAT_INPUT_TYPE", "Can't distinguish what type of input the scanned information is.");
			throw new ElidomRuntimeException(msg);
		}
	}

	@Override
	public Object input(IClassifyInEvent inputEvent) { 
		return this.inputSkuSingle(inputEvent); 
		
	} 
	
	@EventListener(classes = ClassifyRunEvent.class, condition = "#exeEvent.jobType == 'DAS'")
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

	/**
	 * 투입 시 전체 상품에 대한 표시기 점등
	 * 
	 * @param inputEvent
	 * @return
	 */
	private List<JobInstance> indOnByAllMode(IClassifyInEvent inputEvent) {
		// 1. 이벤트에서 데이터 추출 후 투입 상품 코드 Validation & 상품 투입 시퀀스 조회
		JobBatch batch = inputEvent.getJobBatch(); 
		String comCd = inputEvent.getComCd();
		String skuCd = inputEvent.getInputCode();
		int inputSeq = this.validateInputSKU(batch, comCd, skuCd);

		// 2. 이미 투입한 상품이면 검수 - 이미 처리한 작업은 inspect, 처리해야 할 작업은 pick 액션으로 점등
		if(inputSeq >= 1) {
			throw ThrowUtil.newValidationErrorWithNoLog("already-input-sku-want-to-inspection");
			
		// 3. 투입할 상품이면 투입 처리
		} else {
			// 3.1. 작업 인스턴스 조회
			List<JobInstance> jobList = this.serviceDispatcher.getJobStatusService(batch).searchPickingJobList(batch, ValueUtil.newMap("comCd,skuCd", comCd, skuCd));
			
			// 3.2. 투입할 작업 리스트가 없고 투입된 내역이 없다면 에러   
			if(ValueUtil.isEmpty(jobList)) {
				// 투입한 상품으로 처리할 작업이 없습니다
				throw ThrowUtil.newValidationErrorWithNoLog(true, "NO_JOBS_TO_PROCESS_BY_INPUT");
			}
			
			// 3.3 투입 정보 생성
			JobInput newInput = this.doInputSku(batch, comCd, skuCd, jobList);
			// 3.4 호기별로 모든 작업 존 별로 현재 '피킹 시작' 상태인 작업이 없다면 그 존은 점등한다.
			this.startAssorting(batch, newInput, jobList);
			// 3.5 투입 후 처리 이벤트 전송
			this.eventPublisher.publishEvent(new InputEvent(newInput, batch.getJobType()));
			// TODO 3.6 이벤트 핸들러에서 아래 코드 사용 - 호기별 메인 모바일 디바이스(태블릿, PDA)에 새로고침 메시지 전달
			this.sendMessageToMobileDevice(batch, null, null, "info", DeviceCommand.COMMAND_REFRESH);
			// 3.7 작업 리스트 리턴
			return jobList;
		}
	}
	
	/**
	 * 투입 시 투입 수량 만큼만 표시기 점등
	 * 
	 * @param inputEvent
	 * @return
	 */
	private List<JobInstance> indOnByQtyMode(IClassifyInEvent inputEvent) {
		// TODO 
		return null;
	}
	
	@Override
	public Object inputSkuSingle(IClassifyInEvent inputEvent) {
		// 상품 투입시 표시기 점등 모드에 따라 표시기 점등
		if(BatchJobConfigUtil.isInputIndOnAllMode(inputEvent.getJobBatch())) {
			// 한 꺼번에 모든 상품 점등
			return this.indOnByAllMode(inputEvent);
		} else {
			// 투입 수량 만큼만 점등
			return this.indOnByQtyMode(inputEvent);
		}
	}

	@Override
	public Object inputSkuBundle(IClassifyInEvent inputEvent) {
		// 묶음 투입 기능 활성화 여부 체크 
		if(!BatchJobConfigUtil.isBundleInputEnabled(inputEvent.getJobBatch())) {
			// 묶음 투입은 지원하지 않습니다
			throw ThrowUtil.newValidationErrorWithNoLog(true, "NOT_SUPPORTED_METHOD");
		// 수량 기반 표시기 점등 모드로 상품 투입
		} else {
			return this.indOnByQtyMode(inputEvent);
		}
	}

	@Override
	public Object inputSkuBox(IClassifyInEvent inputEvent) {		
		// 완박스 투입 기능 활성화 여부 체크 
		if(!BatchJobConfigUtil.isSingleBoxInputEnabled(inputEvent.getJobBatch())) {
			// 박스 단위 투입은 지원하지 않습니다
			throw ThrowUtil.newValidationErrorWithNoLog(true, "NOT_SUPPORTED_METHOD");
		// 수량 기반 표시기 점등 모드로 상품 투입
		} else {
			return this.indOnByQtyMode(inputEvent);
		}		
	}

	@Override
	public Object inputForInspection(IClassifyInEvent inputEvent) {
		// 1. 현재 작업 배치에 존재하는 상품인지 체크 & 상품 투입 시퀀스 조회
		JobBatch batch = inputEvent.getJobBatch();
		String comCd = inputEvent.getComCd();
		String skuCd = inputEvent.getInputCode();
		int inputSeq = this.validateInputSKU(batch, comCd, skuCd);
		
		// 현재 스캔한 상품 외에 다른 상품이 '피킹 중' 상태가 있는지 체크, 있으면 예외 발생
		List<JobInstance> jobList = this.serviceDispatcher.getJobStatusService(batch).searchPickingJobList(batch, ValueUtil.newMap("status", LogisConstants.JOB_STATUS_PICKING));
		if(ValueUtil.isNotEmpty(jobList)) {
			JobInstance unpickJob = jobList.get(0);
			String msg = MessageUtil.getMessage("NOT_BEEN_COMPLETED_AFTER_INPUT", "투입한 후 완료 처리를 안 한 작업이 있습니다");
			StringJoiner buffer = new StringJoiner(SysConstants.LINE_SEPARATOR).add(msg)
			      .add("[" + unpickJob.getInputSeq() + "]")
				  .add("[" + unpickJob.getSubEquipCd() + "]")
				  .add("[" + unpickJob.getSkuNm() + "]")
				  .add("[" + unpickJob.getPickQty() + "/" + unpickJob.getPickedQty() + "]");
			throw ThrowUtil.newValidationErrorWithNoLog(buffer.toString());
		}		
		
		// 2. 투입할 작업 리스트가 없고 투입된 내역이 없다면 에러
		if(inputSeq == -1) {
			// 투입한 상품으로 처리할 작업이 없습니다
			throw ThrowUtil.newValidationErrorWithNoLog(true, "NO_JOBS_TO_PROCESS_BY_INPUT");
		}
		
		// 3. 투입 순서로 부터 검수
		return this.indOnByInspection(batch, comCd, skuCd, inputSeq);
	}

	@Override
	public void confirmAssort(IClassifyRunEvent exeEvent) {
		// 1. 이벤트로 부터 작업에 필요한 데이터 추출
		JobBatch batch = exeEvent.getJobBatch();
		JobInstance job = exeEvent.getJobInstance();
		int resQty = job.getPickingQty();
		
		// 2. 확정 처리
		if(job.isTodoJob() && job.getPickedQty() < job.getPickQty() && resQty > 0) {
			// 2.1. 작업 정보 업데이트 처리
			job.setPickedQty(job.getPickedQty() + resQty);
			job.setPickingQty(0);
			String status = (job.getPickedQty() >= job.getPickQty()) ?  LogisConstants.JOB_STATUS_FINISH : LogisConstants.JOB_STATUS_PICKING;
			job.setStatus(status);
			this.queryManager.update(job, "pickingQty", "pickedQty", "status", "updatedAt");
			
			// 2.2. 주문 정보 업데이트 처리
			this.updateOrderPickedQtyByConfirm(job, resQty);
		}
		
		// 3. 릴레이 처리
		this.doNextJob(batch, job, exeEvent.getWorkCell(), this.checkCellAssortEnd(job, false));
	}

	@Override
	public void cancelAssort(IClassifyRunEvent exeEvent) {
		// 1. 이벤트로 부터 작업에 필요한 데이터 추출
		JobBatch batch = exeEvent.getJobBatch();
		JobInstance job = exeEvent.getJobInstance();
				
		// 2. 작업이 진행 상태이면 취소 처리
		if(job.isTodoJob() && job.getPickingQty() > 0) {
			job.setPickingQty(0);
			// 취소 옵션이 취소시 '취소 상태'를 관리한다면 '취소 상태'로 변경 
			if(BatchJobConfigUtil.isPickCancelStatusEnabled(batch)) {
				job.setStatus(LogisConstants.JOB_STATUS_CANCEL);				
			}
			this.queryManager.update(job, "status", "pickingQty", "updatedAt");
		}
		
		// 3. 릴레이 처리
		this.doNextJob(batch, job, exeEvent.getWorkCell(), this.checkCellAssortEnd(job, false));
	}

	@Override
	public int splitAssort(IClassifyRunEvent exeEvent) {
		// 1. 이벤트에서 수량 조절을 위한 데이터 추출 
		JobInstance job = exeEvent.getJobInstance();
		WorkCell workCell = exeEvent.getWorkCell();
		int resQty = exeEvent.getResQty();
		
		// 2. 수량 조절 처리 
		if(resQty > 0 && job.isTodoJob()) {
			job = this.splitJob(job, workCell, resQty);			
		}
		
		// 3. 남은 수량 표시기 점등
		this.serviceDispatcher.getIndicationService(job).indicatorOnForPick(job, 0, job.getPickingQty(), 0);

		// 4. 조정 수량 리턴 
		return resQty;
	}

	@Override
	public int undoAssort(IClassifyRunEvent exeEvent) {
		// 1. 작업 데이터 확정 수량 0으로 업데이트 
		JobBatch batch = exeEvent.getJobBatch();
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
		String classFieldName = DasBatchJobConfigUtil.getBoxMappingTargetField(exeEvent.getJobBatch());
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
		this.doNextJob(batch, job, exeEvent.getWorkCell(), this.checkCellAssortEnd(job, false));
		
		// 4. 주문 취소된 확정 수량 리턴
		return pickedQty;
	}

	@EventListener(classes = IClassifyOutEvent.class, condition = "#outEvent.jobType == 'DAS'")
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
		
		// 3. 풀 박스 처리해야 할 작업 리스트 조회
		JobBatch batch = outEvent.getJobBatch();
		Map<String, Object> params = ValueUtil.newMap("classCd,subEquipCd,status", job.getClassCd(), job.getSubEquipCd(), LogisConstants.JOB_STATUS_FINISH);
		List<JobInstance> jobList = this.serviceDispatcher.getJobStatusService(batch).searchJobList(batch, params);
		
		// 4. 풀 박스 처리 
		BoxPack boxPack = this.boxService.fullBoxing(outEvent.getJobBatch(), outEvent.getWorkCell(), jobList, this);
		
		// 5. 다음 작업 처리
		if(boxPack != null) {
			this.doNextJob(batch, job, outEvent.getWorkCell(), this.checkCellAssortEnd(job, false));
		}
		
		// 6. 박스 리턴
		return boxPack;
	}

	@Override
	public BoxPack partialFullboxing(IClassifyOutEvent outEvent) {
		throw ThrowUtil.newNotSupportedMethod();
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
		
		// 3. 박스 리턴
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
		JobInstance splittedJob = AnyValueUtil.populate(job, new JobInstance());
		String nowStr = DateUtil.currentTimeStr();
		
		// 3. 분할 작업 데이터를 완료 처리
		splittedJob.setId(AnyValueUtil.newUuid36());
		splittedJob.setPickQty(splitQty);
		splittedJob.setPickingQty(0);
		splittedJob.setPickedQty(splitQty);
		splittedJob.setPickEndedAt(nowStr);
		splittedJob.setStatus(LogisConstants.JOB_STATUS_FINISH);
		this.queryManager.insert(splittedJob);
		
		// 4. 분할 처리된 주문 정보를 업데이트
		this.updateOrderPickedQtyByConfirm(splittedJob, splitQty);		
		 
		// 5. 기존 작업 데이터의 수량을 분할 처리 후 남은 수량으로 하고 상태는 '피킹 시작' 처리 
		job.setPickQty(job.getPickQty() - splitQty);
		job.setPickingQty(job.getPickQty());
		job.setPickedQty(0);
		job.setStatus(LogisConstants.JOB_STATUS_PICKING);
		job.setPickStartedAt(nowStr);
		this.queryManager.update(job, "pickQty", "pickingQty", "pickedQty", "status", "pickStartedAt", "updatedAt");	
		
		// 6. 기존 작업 데이터 리턴
		return job;
	}
	
	@Override
	public JobInstance findLatestJobForBoxing(Long domainId, String batchId, String cellCd) {
		// 박싱 처리를 위해 로케이션에 존재하는 박스 처리할 작업을 조회
		String sql = "select * from (select * from job_instances where domain_id = :domainId and batch_id = :batchId and sub_equip_cd = :cellCd and status in (:statuses) order by pick_ended_at desc) where rownum <= 1";
		Map<String, Object> params = ValueUtil.newMap("domainId,batchId,cellCd,statuses", domainId, batchId, cellCd, LogisConstants.JOB_STATUS_PF);
		return this.queryManager.selectBySql(sql, params, JobInstance.class);
	}
	
	@Override
	public boolean checkStationJobsEnd(JobInstance job, String stationCd) {
		// 릴레이 처리를 위해 작업 스테이션에서 작업이 끝났는지 체크 ...
		String sql = this.dasQueryStore.getSearchPickingJobListQuery();
		Map<String, Object> params = ValueUtil.newMap("domainId,batchId,classCd,stationCd,statuses", job.getDomainId(), job.getBatchId(), job.getClassCd(), stationCd, LogisConstants.JOB_STATUS_WIPC);
		return this.queryManager.selectSizeBySql(sql, params) == 0;
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
	public boolean checkEndClassifyAll(JobBatch batch) {
		Query condition = AnyOrmUtil.newConditionForExecution(batch.getDomainId());
		condition.addFilter("batchId", batch.getId());
		condition.addFilter("statuses", SysConstants.IN, LogisConstants.JOB_STATUS_WIPC);
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
	
	/**
	 * 검수 기능으로 표시기 점등
	 * 
	 * @param batch
	 * @param comCd
	 * @param skuCd
	 * @param inputSeq
	 * @return
	 */
	private List<JobInstance> indOnByInspection(JobBatch batch, String comCd, String skuCd, int inputSeq) {
		// 1. 투입 순서로 부터 작업 리스트 조회, --> FIXME 처리한 작업의 경우 로케이션 별로 마지막에 처리한 작업만 조회가 되도록 수정, 처리할 작업은 그대로 ... 
		List<JobInstance> jobList = this.serviceDispatcher.getJobStatusService(batch).searchPickingJobList(batch, ValueUtil.newMap("comCd,skuCd,inputSeq", comCd, skuCd, inputSeq));
		// 2. 없으면 완료된 작업과 처리할 작업 리스트를 분리 
		List<JobInstance> doneJobList = jobList.stream().filter(job -> (job.isDoneJob())).collect(Collectors.toList());
		List<JobInstance> todoJobList = jobList.stream().filter(job -> (job.isTodoJob())).collect(Collectors.toList());
		// 3. inspectJobList는 inspection 모드로 표시기 점등
		IIndicationService indSvc = this.serviceDispatcher.getIndicationService(batch);
		// 4. 검수 모드로 표시기 점등 
		indSvc.indicatorsOn(batch, false, GwConstants.IND_ACTION_TYPE_INSPECT, doneJobList);
		// 5. picking 모드로 표시기 점등
		indSvc.indicatorsOn(batch, false, todoJobList);
		// 6. PDA, Tablet, KIOSK 등에 표시할 작업 리스트 리턴
		return jobList;
	}

	/**
	 * 상품 투입 전 Validation 체크
	 * 
	 * @param batch
	 * @param comCd
	 * @param skuCd
	 * @return 작업 배치 내 상품 투입 시퀀스 
	 */
	private int validateInputSKU(JobBatch batch, String comCd, String skuCd) {
		// 투입 상품이 현재 작업 배치에 존재하는 상품인지 체크
		Long domainId = batch.getDomainId();
		Query condition = AnyOrmUtil.newConditionForExecution(domainId);
		condition.addFilter("batchId", batch.getId());
		condition.addFilter("comCd", comCd);
		condition.addFilter("skuCd", skuCd);
		condition.addFilter("equipCd", batch.getEquipCd());
		
		if(this.queryManager.selectSize(JobInstance.class, condition) == 0) {
			// 상품 코드 조회, 없으면 상품 코드로 상품을 찾을 수 없습니다.
			AnyEntityUtil.findEntityBy(domainId, true, SKU.class, "comCd,skuCd,skuBarcd", "domainId,comCd,skuCd", domainId, comCd, skuCd);
			// 스캔한 상품은 현재 작업 배치에 존재하지 않습니다
			throw ThrowUtil.newValidationErrorWithNoLog(true, "NO_SKU_FOUND_IN_SCOPE", "terms.label.job_batch");
			
		} else {
			// 상품의 투입 시퀀스를 조회 
			return this.findSkuInputSeq(batch, comCd, skuCd);
		}
	}

	/**
	 * 상품 코드로 투입 시퀀스를 조회
	 * 
	 * @param batch
	 * @param comCd
	 * @param skuCd
	 * @return
	 */
	private int findSkuInputSeq(JobBatch batch, String comCd, String skuCd) {
		Map<String, Object> params = ValueUtil.newMap("domainId,batchId,equipCd,comCd,skuCd", batch.getDomainId(), batch.getId(), batch.getEquipCd(), comCd, skuCd);
		String sql = this.dasQueryStore.getDasFindInputSeqBySkuQuery();
		int inputSeq = this.queryManager.selectBySql(sql, params, Integer.class);
		return inputSeq > 0 ? inputSeq : -1;
	}
	
	/**
	 * 투입 처리 
	 * 
	 * @param batch
	 * @param comCd
	 * @param skuCd
	 * @param jobList
	 * @return
	 */
	private JobInput doInputSku(JobBatch batch, String comCd, String skuCd, List<JobInstance> jobList) {
		// 1. 작업 리스트로 부터 매장 개수 구하기 
		Long classCnt = jobList.stream().map(job -> job.getShopCd()).distinct().count();
				
		// 2. 투입 정보 생성
		JobInstance firstJob = jobList.get(0);
		int nextInputSeq = this.serviceDispatcher.getJobStatusService(batch).findNextInputSeq(batch);
		JobInput newInput = new JobInput();
		newInput.setDomainId(batch.getDomainId());
		newInput.setBatchId(batch.getId());
		newInput.setEquipType(LogisConstants.EQUIP_TYPE_RACK);
		newInput.setEquipCd(batch.getEquipCd());
		newInput.setStationCd(firstJob.getStationCd());
		newInput.setInputSeq(nextInputSeq);
		newInput.setComCd(comCd);
		newInput.setSkuCd(skuCd);
		// TODO 투입 유형을 설정에서 조회해서 혹은 화면에서 직접 넘겨주기 ...
		newInput.setInputType(LogisCodeConstants.JOB_INPUT_TYPE_PCS);
		newInput.setInputQty(classCnt.intValue());
		newInput.setStatus(JobInput.INPUT_STATUS_WAIT);
		// 이전 투입에 대한 컬러 조회
		IIndicationService indSvc = this.serviceDispatcher.getIndicationService(batch);
		String prevColor = indSvc.prevIndicatorColor(firstJob);
		String currentColor = indSvc.nextIndicatorColor(firstJob, prevColor);
		newInput.setColorCd(currentColor);
		this.queryManager.insert(newInput);
		
		// 3. 투입 작업 리스트 업데이트 
		String currentTime = DateUtil.currentTimeStr();
		for(JobInstance job : jobList) {
			job.setStatus(LogisConstants.JOB_STATUS_INPUT);
			job.setInputSeq(nextInputSeq);
			job.setColorCd(currentColor);
			job.setInputAt(currentTime);
			job.setPickStartedAt(currentTime);
			job.setPickingQty(job.getPickQty());
		}
		
		// 4. 작업 정보 업데이트
		this.queryManager.updateBatch(jobList, "status", "pickingQty", "colorCd", "inputSeq", "pickStartedAt", "inputAt", "updaterId", "updatedAt");
		
		// 5. 투입 정보 리턴
		return newInput;
	}
	
	/**
	 * 작업 존에 분류 처리를 위한 표시기 점등
	 * 
	 * @param batch
	 * @param input
	 * @param jobList
	 */
	private void startAssorting(JobBatch batch, JobInput input, List<JobInstance> jobList) {		
		// 1. 배치 호기별로 표시기 점등이 안 되어 있는 존을 조회하여 해당 존의 표시기 리스트를 점등한다.
		List<String> stationList = AnyValueUtil.filterValueListBy(jobList, "stationCd");
		
		// 2. 작업 배치 내 작업 존 리스트 내에 피킹 (표시기 점등) 중인 작업이 있는 작업 존 리스트를 조회
		String sql = this.dasQueryStore.getDasSearchWorkingStationQuery();
		Map<String, Object> params = ValueUtil.newMap("domainId,batchId,inputSeq,status,stationList", input.getDomainId(), input.getBatchId(), input.getInputSeq(), LogisConstants.JOB_STATUS_PICKING, stationList);
		List<String> pickingStationList = this.queryManager.selectListBySql(sql, params, String.class, 0, 0);
		
		// 3. 피킹된 (즉 작업 중인) 작업 존 외에 존재하는 작업 리스트를 대상으로 표시기 점등
		List<JobInstance> indJobList = jobList.stream().filter(job -> (!pickingStationList.contains(job.getStationCd()))).collect(Collectors.toList());
		
		// 4. 표시기 점등
		this.serviceDispatcher.getIndicationService(batch).indicatorsOn(batch, false, indJobList);
	}

	/**
	 * 다음 작업 처리
	 * 
	 * @param batch
	 * @param job
	 * @param cell
	 * @param cellEndFlag
	 */
	private void doNextJob(JobBatch batch, JobInstance job, WorkCell cell, boolean cellEndFlag) {
		// 1. 해당 로케이션의 작업이 모두 완료 상태인지 체크
		if(cellEndFlag) {
			this.finishAssortCell(job, cell, cellEndFlag);
		// 2. 릴레이 처리
		} else {
			this.relayLightOn(batch, job, job.getStationCd());
		}
	}

	/**
	 * 현재 작업 투입 순서 이후의 투입 작업 중에 현재 작업 존과 같은 곳에 작업 리스트 조회 
	 * 
	 * @param batch
	 * @param job
	 * @param stationCd
	 */
	private void relayLightOn(JobBatch batch, JobInstance job, String stationCd) {
		// 1. 릴레이 처리를 위한 다음 처리할 InputSeq를 조회
		StringJoiner sql = new StringJoiner(SysConstants.LINE_SEPARATOR);
		sql.add("SELECT")
		   .add("	NVL(MIN(JOB.INPUT_SEQ), -2) AS INPUT_SEQ")
		   .add("FROM")
		   .add("	JOB_INSTANCE JOB INNER JOIN CELLS CELL ON JOB.DOMAIN_ID = CELL.DOMAIN_ID AND JOB.SUB_EQUIP_CD = CELL.CELL_CD")
		   .add("WHERE")
		   .add(" 	JOB.DOMAIN_ID = :domainId")
		   .add(" 	AND JOB.BATCH_ID = :batchId")
		   .add(" 	AND JOB.INPUT_SEQ > :inputSeq")
		   .add("   AND STATUS in (:statuses)")
		   .add(" 	AND CELL.EQUIP_CD = :equipCd")
		   .add(" 	AND CELL.STATION_CD = :stationCd");
		
		Long domainId = batch.getDomainId();
		Map<String, Object> params = ValueUtil.newMap("domainId,batchId,statuses,equipCd,stationCd", domainId, batch.getId(), LogisConstants.JOB_STATUS_IP, batch.getEquipCd(), stationCd);
		int nextInputSeq = this.queryManager.selectBySql(sql.toString(), params, Integer.class) + 1;
				
        // 2. 현재 작업 투입 순서 이후의 투입 작업 중에 현재 작업 존과 같은 곳에 작업 리스트 조회
		params = ValueUtil.newMap("stationCd,status,inputSeq", stationCd, LogisConstants.JOB_STATUS_INPUT, nextInputSeq);
        List<JobInstance> relayJobs = this.serviceDispatcher.getJobStatusService(batch).searchPickingJobList(batch, params);
        
        if(ValueUtil.isNotEmpty(relayJobs)) {
            // 2.1. 릴레이 작업에 포함된 작업에 소속된 투입 정보의 상태를 '진행 중'으로 변경
        	JobInstance relayJob = relayJobs.get(0);
        	JobInput jobInput = AnyEntityUtil.findEntityBy(domainId, true, JobInput.class, null, "domainId,batchId,equipCd,stationCd,inputSeq,skuCd", domainId, batch.getId(), batch.getEquipCd(), stationCd, relayJob.getInputSeq(), relayJob.getSkuCd());
            jobInput.setStatus(JobInput.INPUT_STATUS_RUNNING);
            this.queryManager.update(jobInput, "status", "updatedAt");
            
            // 2.2 작업 리스트로 표시기 점등
            if(job.getPickingQty() > 0) {
				this.serviceDispatcher.getIndicationService(job).indicatorOnForPick(job, 0, job.getPickingQty(), 0);
            }
        }     
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
			deviceList = DasBatchJobConfigUtil.getDeviceList(batch) == null ? null : DasBatchJobConfigUtil.getDeviceList(batch);
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
