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

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.Before;
import org.junit.Test;
import org.openmrs.module.queue.api.search.QueueEntrySearchCriteria;
import org.openmrs.module.queue.model.Queue;
import org.openmrs.module.queue.model.QueueEntry;

public class AutoCloseQueueEntryTaskTest {
	
	final List<QueueEntry> queueEntries = new ArrayList<>();
	
	private String configuredTime;
	
	private List<Queue> configuredQueues;
	
	private Date now;
	
	class TestAutoCloseQueueEntryTask extends AutoCloseQueueEntryTask {
		
		@Override
		protected String getConfiguredCloseTime() {
			return configuredTime;
		}
		
		@Override
		protected List<Queue> getQueuesToClear() {
			return configuredQueues;
		}
		
		@Override
		protected Date now() {
			return now;
		}
		
		@Override
		protected List<QueueEntry> getQueueEntries(QueueEntrySearchCriteria criteria) {
			// Emulate the DB-level filtering that getQueueEntries would normally perform
			return queueEntries.stream().filter(e -> e.getEndedAt() == null)
			        .filter(e -> e.getStartedAt() == null || !e.getStartedAt().after(criteria.getStartedOnOrBefore()))
			        .filter(e -> criteria.getQueues() == null || criteria.getQueues().contains(e.getQueue()))
			        .collect(Collectors.toList());
		}
		
		@Override
		protected void saveQueueEntry(QueueEntry queueEntry) {
			// Do nothing
		}
	}
	
	@Before
	public void setup() throws Exception {
		queueEntries.clear();
		configuredQueues = null;
		now = getDate("2020-01-01 23:59");
	}
	
	@Test
	public void shouldDoNothingWhenTimeIsBlank() throws Exception {
		configuredTime = "";
		QueueEntry queueEntry = queueEntryStartedAt("2020-01-01 09:00", null);
		
		new TestAutoCloseQueueEntryTask().run();
		assertThat(queueEntry.getEndedAt(), nullValue());
	}
	
	@Test
	public void shouldDoNothingWhenTimeIsUnparseable() throws Exception {
		configuredTime = "nonsense";
		QueueEntry queueEntry = queueEntryStartedAt("2020-01-01 09:00", null);
		
		new TestAutoCloseQueueEntryTask().run();
		assertThat(queueEntry.getEndedAt(), nullValue());
	}
	
	@Test
	public void shouldNotClearBeforeConfiguredTime() throws Exception {
		configuredTime = "23:59";
		now = getDate("2020-01-01 17:00");
		QueueEntry queueEntry = queueEntryStartedAt("2020-01-01 09:00", null);
		
		new TestAutoCloseQueueEntryTask().run();
		assertThat(queueEntry.getEndedAt(), nullValue());
	}
	
	@Test
	public void shouldClearActiveEntriesAtOrAfterConfiguredTime() throws Exception {
		configuredTime = "23:59";
		QueueEntry queueEntry = queueEntryStartedAt("2020-01-01 09:00", null);
		
		new TestAutoCloseQueueEntryTask().run();
		assertThat(queueEntry.getEndedAt(), equalTo(now));
	}
	
	@Test
	public void shouldNotClearEntriesStartedAfterConfiguredTime() throws Exception {
		configuredTime = "18:00";
		now = getDate("2020-01-01 18:30");
		QueueEntry beforeCloseTime = queueEntryStartedAt("2020-01-01 09:00", null);
		QueueEntry afterCloseTime = queueEntryStartedAt("2020-01-01 18:15", null);
		
		new TestAutoCloseQueueEntryTask().run();
		assertThat(beforeCloseTime.getEndedAt(), notNullValue());
		assertThat(afterCloseTime.getEndedAt(), nullValue());
	}
	
	@Test
	public void shouldOnlyClearConfiguredQueues() throws Exception {
		configuredTime = "23:59";
		Queue queueA = new Queue();
		Queue queueB = new Queue();
		configuredQueues = new ArrayList<>();
		configuredQueues.add(queueA);
		
		QueueEntry inQueueA = queueEntryStartedAt("2020-01-01 09:00", queueA);
		QueueEntry inQueueB = queueEntryStartedAt("2020-01-01 09:00", queueB);
		
		new TestAutoCloseQueueEntryTask().run();
		assertThat(inQueueA.getEndedAt(), notNullValue());
		assertThat(inQueueB.getEndedAt(), nullValue());
	}
	
	private QueueEntry queueEntryStartedAt(String startedAt, Queue queue) throws Exception {
		QueueEntry queueEntry = new QueueEntry();
		queueEntry.setStartedAt(getDate(startedAt));
		queueEntry.setQueue(queue);
		queueEntries.add(queueEntry);
		return queueEntry;
	}
	
	Date getDate(String dateStr) throws Exception {
		DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm");
		return df.parse(dateStr);
	}
}
