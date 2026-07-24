/*
 * This Source Code Form is subject to the terms of the Mozilla Public License,
 * v. 2.0. If a copy of the MPL was not distributed with this file, You can
 * obtain one at http://mozilla.org/MPL/2.0/. OpenMRS is also distributed under
 * the terms of the Healthcare Disclaimer located at http://openmrs.org/license.
 *
 * Copyright (C) OpenMRS Inc. OpenMRS is a registered trademark and the OpenMRS
 * graphic logo is a trademark of OpenMRS Inc.
 */
package org.openmrs.module.queue.tasks;

import static org.openmrs.module.queue.QueueModuleConstants.AUTO_CLOSE_QUEUE_ENTRIES_AT_TIME;
import static org.openmrs.module.queue.QueueModuleConstants.AUTO_CLOSE_QUEUE_ENTRIES_FOR_QUEUES;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.openmrs.api.context.Context;
import org.openmrs.module.queue.api.QueueServicesWrapper;
import org.openmrs.module.queue.api.search.QueueEntrySearchCriteria;
import org.openmrs.module.queue.model.Queue;
import org.openmrs.module.queue.model.QueueEntry;

/**
 * This ends all active queue entries in the configured queues once per day at a configured time of
 * day (default: end of day). The set of queues to clear and the time to clear them are both
 * controlled by global properties. Only entries that were already active at the configured time are
 * ended, so patients added later in the day are left in the queue.
 */
@Slf4j
public class AutoCloseQueueEntryTask implements Runnable {
	
	private static final String TIME_FORMAT = "HH:mm";
	
	private static final AtomicBoolean currentlyExecuting = new AtomicBoolean(false);
	
	@Override
	public void run() {
		if (!currentlyExecuting.compareAndSet(false, true)) {
			log.debug("AutoCloseQueueEntryTask is still executing, not running again");
			return;
		}
		log.debug("Executing AutoCloseQueueEntryTask");
		try {
			String configuredTime = getConfiguredCloseTime();
			if (StringUtils.isBlank(configuredTime)) {
				log.debug("No auto-close time configured, not clearing any queue entries");
				return;
			}
			
			Date now = now();
			Date closeTime = getCloseTimeForToday(configuredTime.trim(), now);
			if (closeTime == null) {
				return;
			}
			if (now.before(closeTime)) {
				log.debug("Current time is before the configured auto-close time {}, nothing to do", configuredTime);
				return;
			}
			
			List<Queue> queues = getQueuesToClear();
			if (queues != null && queues.isEmpty()) {
				log.debug("No queues configured for auto-close, nothing to do");
				return;
			}
			
			QueueEntrySearchCriteria criteria = new QueueEntrySearchCriteria();
			criteria.setIsEnded(Boolean.FALSE);
			criteria.setStartedOnOrBefore(closeTime);
			criteria.setQueues(queues);
			
			List<QueueEntry> queueEntries = getQueueEntries(criteria);
			log.debug("There are {} queue entries to auto-close", queueEntries.size());
			for (QueueEntry queueEntry : queueEntries) {
				closeQueueEntry(queueEntry, now);
			}
		}
		catch (Exception e) {
			log.error("AutoCloseQueueEntryTask failed to complete", e);
		}
		finally {
			currentlyExecuting.set(false);
		}
	}
	
	private void closeQueueEntry(QueueEntry queueEntry, Date endedAt) {
		try {
			queueEntry.setEndedAt(endedAt);
			saveQueueEntry(queueEntry);
			log.info("Queue entry auto-closed on schedule: {}", queueEntry.getQueueEntryId());
		}
		catch (Exception e) {
			Context.evictFromSession(queueEntry);
			log.warn("Unable to auto-close queue entry {}", queueEntry.getQueueEntryId(), e);
		}
	}
	
	/**
	 * Parses the configured HH:mm time and returns the corresponding instant on the same day as the
	 * given reference date. Returns null if the configured value cannot be parsed.
	 */
	protected Date getCloseTimeForToday(String configuredTime, Date referenceDate) {
		try {
			SimpleDateFormat format = new SimpleDateFormat(TIME_FORMAT);
			format.setLenient(false);
			Calendar parsed = Calendar.getInstance();
			parsed.setTime(format.parse(configuredTime));
			
			Calendar closeTime = Calendar.getInstance();
			closeTime.setTime(referenceDate);
			closeTime.set(Calendar.HOUR_OF_DAY, parsed.get(Calendar.HOUR_OF_DAY));
			closeTime.set(Calendar.MINUTE, parsed.get(Calendar.MINUTE));
			closeTime.set(Calendar.SECOND, 0);
			closeTime.set(Calendar.MILLISECOND, 0);
			return closeTime.getTime();
		}
		catch (ParseException e) {
			log.warn("Invalid value '{}' for global property {}, expected format {}", configuredTime,
			    AUTO_CLOSE_QUEUE_ENTRIES_AT_TIME, TIME_FORMAT);
			return null;
		}
	}
	
	/**
	 * @return the configured time of day (HH:mm) at which to clear queue entries, or blank/null if
	 *         auto-clearing is disabled
	 */
	protected String getConfiguredCloseTime() {
		return getServices().getAdministrationService().getGlobalProperty(AUTO_CLOSE_QUEUE_ENTRIES_AT_TIME);
	}
	
	/**
	 * @return the queues whose entries should be cleared, or null to clear entries in all queues
	 */
	protected List<Queue> getQueuesToClear() {
		String configuredQueues = getServices().getAdministrationService()
		        .getGlobalProperty(AUTO_CLOSE_QUEUE_ENTRIES_FOR_QUEUES);
		if (StringUtils.isBlank(configuredQueues)) {
			return null;
		}
		List<Queue> queues = new ArrayList<>();
		for (String queueRef : configuredQueues.split(",")) {
			String trimmed = queueRef.trim();
			if (StringUtils.isBlank(trimmed)) {
				continue;
			}
			try {
				queues.add(getServices().getQueue(trimmed));
			}
			catch (IllegalArgumentException e) {
				log.warn("Ignoring unknown queue '{}' configured in global property {}", trimmed,
				    AUTO_CLOSE_QUEUE_ENTRIES_FOR_QUEUES);
			}
		}
		return queues;
	}
	
	/**
	 * @param criteria the criteria identifying the queue entries to end
	 * @return the queue entries matching the given criteria
	 */
	protected List<QueueEntry> getQueueEntries(QueueEntrySearchCriteria criteria) {
		return getServices().getQueueEntryService().getQueueEntries(criteria);
	}
	
	/**
	 * @param queueEntry the QueueEntry to save
	 */
	protected void saveQueueEntry(QueueEntry queueEntry) {
		getServices().getQueueEntryService().saveQueueEntry(queueEntry);
	}
	
	/**
	 * @return the current time; overridable to allow deterministic testing
	 */
	protected Date now() {
		return new Date();
	}
	
	protected QueueServicesWrapper getServices() {
		return Context.getRegisteredComponent("queue.QueueServicesWrapper", QueueServicesWrapper.class);
	}
}
