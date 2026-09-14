/**
 * SPDX-FileCopyrightText: (c) 2000 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.translation.service.test;

import com.liferay.arquillian.extension.junit.bridge.junit.Arquillian;
import com.liferay.asset.kernel.model.AssetCategory;
import com.liferay.asset.kernel.model.AssetEntry;
import com.liferay.asset.kernel.model.AssetVocabulary;
import com.liferay.asset.kernel.service.AssetCategoryLocalService;
import com.liferay.asset.kernel.service.AssetEntryLocalService;
import com.liferay.asset.kernel.service.AssetVocabularyLocalService;
import com.liferay.journal.model.JournalArticle;
import com.liferay.journal.service.JournalArticleLocalService;
import com.liferay.journal.test.util.JournalTestUtil;
import com.liferay.object.constants.ObjectDefinitionConstants;
import com.liferay.object.constants.ObjectEntryFolderConstants;
import com.liferay.object.field.builder.TextObjectFieldBuilder;
import com.liferay.object.model.ObjectDefinition;
import com.liferay.object.model.ObjectEntry;
import com.liferay.object.service.ObjectEntryLocalService;
import com.liferay.object.test.util.ObjectDefinitionTestUtil;
import com.liferay.portal.kernel.model.Group;
import com.liferay.portal.kernel.service.CompanyLocalService;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.service.WorkflowDefinitionLinkLocalService;
import com.liferay.portal.kernel.service.WorkflowInstanceLinkLocalService;
import com.liferay.portal.kernel.test.TestInfo;
import com.liferay.portal.kernel.test.rule.AggregateTestRule;
import com.liferay.portal.kernel.test.rule.DeleteAfterTestRun;
import com.liferay.portal.kernel.test.util.GroupTestUtil;
import com.liferay.portal.kernel.test.util.RandomTestUtil;
import com.liferay.portal.kernel.test.util.ServiceContextTestUtil;
import com.liferay.portal.kernel.test.util.TestPropsValues;
import com.liferay.portal.kernel.theme.ThemeDisplay;
import com.liferay.portal.kernel.util.ArrayUtil;
import com.liferay.portal.kernel.util.HashMapBuilder;
import com.liferay.portal.kernel.util.LocaleUtil;
import com.liferay.portal.kernel.util.StringUtil;
import com.liferay.portal.kernel.util.WebKeys;
import com.liferay.portal.kernel.workflow.WorkflowConstants;
import com.liferay.portal.kernel.workflow.WorkflowHandlerRegistryUtil;
import com.liferay.portal.test.rule.Inject;
import com.liferay.portal.test.rule.LiferayIntegrationTestRule;
import com.liferay.portal.test.rule.PermissionCheckerMethodTestRule;
import com.liferay.translation.manager.TranslationManager;
import com.liferay.translation.model.TranslationEntry;
import com.liferay.translation.service.TranslationEntryLocalService;
import com.liferay.translation.test.util.TranslationTestUtil;

import jakarta.servlet.http.HttpServletRequest;

import java.io.File;
import java.io.FileInputStream;
import java.io.Serializable;

import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import org.springframework.mock.web.MockHttpServletRequest;

/**
 * @author Alejandro Tardín
 */
@RunWith(Arquillian.class)
public class TranslationEntryLocalServiceTest {

	@ClassRule
	@Rule
	public static final AggregateTestRule aggregateTestRule =
		new AggregateTestRule(
			new LiferayIntegrationTestRule(),
			PermissionCheckerMethodTestRule.INSTANCE);

	@Before
	public void setUp() throws Exception {
		_group = GroupTestUtil.addGroup();
	}

	@Test
	@TestInfo("LPD-102310")
	public void testAddOrUpdateTranslationEntryDoesNotClearAssetCategoryIdsAndAssetTagNames()
		throws Exception {

		ObjectDefinition objectDefinition =
			ObjectDefinitionTestUtil.publishObjectDefinition(
				Collections.singletonList(
					new TextObjectFieldBuilder(
					).labelMap(
						Collections.singletonMap(
							LocaleUtil.getDefault(), "Title")
					).localized(
						true
					).name(
						"title"
					).build()),
				ObjectDefinitionConstants.SCOPE_SITE);

		AssetVocabulary assetVocabulary =
			_assetVocabularyLocalService.addVocabulary(
				TestPropsValues.getUserId(), _group.getGroupId(),
				RandomTestUtil.randomString(),
				ServiceContextTestUtil.getServiceContext(_group.getGroupId()));

		AssetCategory assetCategory = _assetCategoryLocalService.addCategory(
			TestPropsValues.getUserId(), _group.getGroupId(),
			RandomTestUtil.randomString(), assetVocabulary.getVocabularyId(),
			ServiceContextTestUtil.getServiceContext(_group.getGroupId()));

		String tagName = RandomTestUtil.randomString();

		ServiceContext addServiceContext =
			ServiceContextTestUtil.getServiceContext(_group.getGroupId());

		addServiceContext.setAssetCategoryIds(
			new long[] {assetCategory.getCategoryId()});
		addServiceContext.setAssetTagNames(new String[] {tagName});

		ObjectEntry objectEntry = _objectEntryLocalService.addObjectEntry(
			_group.getGroupId(), TestPropsValues.getUserId(),
			objectDefinition.getObjectDefinitionId(),
			ObjectEntryFolderConstants.PARENT_OBJECT_ENTRY_FOLDER_ID_DEFAULT,
			"en_US",
			HashMapBuilder.<String, Serializable>put(
				"title_i18n",
				(Serializable)HashMapBuilder.put(
					"en_US", RandomTestUtil.randomString()
				).build()
			).build(),
			addServiceContext);

		AssetEntry assetEntry = _assetEntryLocalService.getEntry(
			objectDefinition.getClassName(), objectEntry.getObjectEntryId());

		Assert.assertEquals(
			Collections.singletonList(assetCategory.getCategoryId()),
			Arrays.asList(ArrayUtil.toArray(assetEntry.getCategoryIds())));
		Assert.assertEquals(
			Collections.singletonList(tagName),
			Arrays.asList(assetEntry.getTagNames()));

		File xliffFile = _translationManager.getXLIFFFile(
			objectDefinition.getClassName(), objectEntry.getObjectEntryId(),
			"application/xliff+xml", LocaleUtil.US, "en_US", "es_ES");

		ThemeDisplay themeDisplay = new ThemeDisplay();

		themeDisplay.setCompany(
			_companyLocalService.getCompany(_group.getCompanyId()));
		themeDisplay.setLocale(LocaleUtil.US);
		themeDisplay.setScopeGroupId(_group.getGroupId());
		themeDisplay.setSiteGroupId(_group.getGroupId());
		themeDisplay.setUser(TestPropsValues.getUser());

		HttpServletRequest httpServletRequest = new MockHttpServletRequest();

		httpServletRequest.setAttribute(WebKeys.THEME_DISPLAY, themeDisplay);

		ServiceContext translateServiceContext =
			ServiceContextTestUtil.getServiceContext(_group.getGroupId());

		translateServiceContext.setRequest(httpServletRequest);

		_translationEntryLocalService.addOrUpdateTranslationEntry(
			_group.getGroupId(), objectDefinition.getClassName(),
			objectEntry.getObjectEntryId(),
			StringUtil.read(new FileInputStream(xliffFile)),
			"application/xliff+xml", "es_ES", translateServiceContext);

		assetEntry = _assetEntryLocalService.getEntry(
			objectDefinition.getClassName(), objectEntry.getObjectEntryId());

		Assert.assertEquals(
			Collections.singletonList(assetCategory.getCategoryId()),
			Arrays.asList(ArrayUtil.toArray(assetEntry.getCategoryIds())));
		Assert.assertEquals(
			Collections.singletonList(tagName),
			Arrays.asList(assetEntry.getTagNames()));
	}

	@Test
	public void testAddOrUpdateTranslationEntryDoesNotDeleteTranslationEntryOnPublish()
		throws Exception {

		JournalArticle journalArticle = JournalTestUtil.addArticle(
			_group.getGroupId(), "test title", "test content");

		_translationEntryLocalService.addOrUpdateTranslationEntry(
			_group.getGroupId(), JournalArticle.class.getName(),
			journalArticle.getResourcePrimKey(),
			StringUtil.replace(
				TranslationTestUtil.readFileToString(
					"test-journal-article-simple.xlf"),
				"[$JOURNAL_ARTICLE_ID$]",
				String.valueOf(journalArticle.getResourcePrimKey())),
			"application/xliff+xml", LocaleUtil.toLanguageId(LocaleUtil.SPAIN),
			ServiceContextTestUtil.getServiceContext(_group.getGroupId()));

		Assert.assertNotNull(
			_translationEntryLocalService.fetchTranslationEntry(
				JournalArticle.class.getName(),
				journalArticle.getResourcePrimKey(),
				LocaleUtil.toLanguageId(LocaleUtil.SPAIN)));
	}

	@Test
	public void testAddOrUpdateTranslationEntryDoesNotWriteToTheJournalArticleOnDraft()
		throws Exception {

		JournalArticle journalArticle = JournalTestUtil.addArticle(
			_group.getGroupId(), "test title", "test content");

		ServiceContext serviceContext =
			ServiceContextTestUtil.getServiceContext(_group.getGroupId());

		serviceContext.setWorkflowAction(WorkflowConstants.ACTION_SAVE_DRAFT);

		_translationEntryLocalService.addOrUpdateTranslationEntry(
			_group.getGroupId(), JournalArticle.class.getName(),
			journalArticle.getResourcePrimKey(),
			StringUtil.replace(
				TranslationTestUtil.readFileToString(
					"test-journal-article-simple.xlf"),
				"[$JOURNAL_ARTICLE_ID$]",
				String.valueOf(journalArticle.getResourcePrimKey())),
			"application/xliff+xml", LocaleUtil.toLanguageId(LocaleUtil.SPAIN),
			serviceContext);

		journalArticle = _journalArticleLocalService.fetchLatestArticle(
			journalArticle.getResourcePrimKey());

		Assert.assertEquals(
			"test title", journalArticle.getTitle(LocaleUtil.SPAIN));

		Assert.assertNotNull(
			_translationEntryLocalService.fetchTranslationEntry(
				JournalArticle.class.getName(),
				journalArticle.getResourcePrimKey(),
				LocaleUtil.toLanguageId(LocaleUtil.SPAIN)));
	}

	@Test
	public void testAddOrUpdateTranslationEntryWritesToTheJournalArticleOnPublish()
		throws Exception {

		JournalArticle journalArticle = JournalTestUtil.addArticle(
			_group.getGroupId(), "test title", "test content");

		_translationEntryLocalService.addOrUpdateTranslationEntry(
			_group.getGroupId(), JournalArticle.class.getName(),
			journalArticle.getResourcePrimKey(),
			StringUtil.replace(
				TranslationTestUtil.readFileToString(
					"test-journal-article-simple.xlf"),
				"[$JOURNAL_ARTICLE_ID$]",
				String.valueOf(journalArticle.getResourcePrimKey())),
			"application/xliff+xml", LocaleUtil.toLanguageId(LocaleUtil.SPAIN),
			ServiceContextTestUtil.getServiceContext(_group.getGroupId()));

		journalArticle = _journalArticleLocalService.fetchLatestArticle(
			journalArticle.getResourcePrimKey());

		Assert.assertEquals(
			"título de pruebas", journalArticle.getTitle(LocaleUtil.SPAIN));
	}

	@Test
	public void testDeleteTranslationEntryDeletesWorkflowLink()
		throws Exception {

		_workflowDefinitionLinkLocalService.updateWorkflowDefinitionLink(
			TestPropsValues.getUserId(), TestPropsValues.getCompanyId(),
			_group.getGroupId(), TranslationEntry.class.getName(), 0, 0,
			"Single Approver@1");

		JournalArticle journalArticle = JournalTestUtil.addArticle(
			_group.getGroupId(), "test title", "test content");

		TranslationEntry translationEntry =
			_translationEntryLocalService.addOrUpdateTranslationEntry(
				_group.getGroupId(), JournalArticle.class.getName(),
				journalArticle.getResourcePrimKey(),
				StringUtil.replace(
					TranslationTestUtil.readFileToString(
						"test-journal-article-simple.xlf"),
					"[$JOURNAL_ARTICLE_ID$]",
					String.valueOf(journalArticle.getResourcePrimKey())),
				"application/xliff+xml",
				LocaleUtil.toLanguageId(LocaleUtil.SPAIN),
				ServiceContextTestUtil.getServiceContext(_group.getGroupId()));

		WorkflowHandlerRegistryUtil.startWorkflowInstance(
			TestPropsValues.getCompanyId(), translationEntry.getGroupId(),
			TestPropsValues.getUserId(), TranslationEntry.class.getName(),
			translationEntry.getTranslationEntryId(), translationEntry,
			new ServiceContext());

		translationEntry = _translationEntryLocalService.fetchTranslationEntry(
			JournalArticle.class.getName(), journalArticle.getResourcePrimKey(),
			LocaleUtil.toLanguageId(LocaleUtil.SPAIN));

		Assert.assertNotNull(
			_workflowInstanceLinkLocalService.getWorkflowInstanceLink(
				TestPropsValues.getCompanyId(), translationEntry.getGroupId(),
				TranslationEntry.class.getName(),
				translationEntry.getTranslationEntryId()));

		_translationEntryLocalService.deleteTranslationEntry(
			translationEntry.getTranslationEntryId());

		Assert.assertNull(
			_workflowInstanceLinkLocalService.fetchWorkflowInstanceLink(
				TestPropsValues.getCompanyId(), translationEntry.getGroupId(),
				TranslationEntry.class.getName(),
				translationEntry.getTranslationEntryId()));
	}

	@Inject
	private AssetCategoryLocalService _assetCategoryLocalService;

	@Inject
	private AssetEntryLocalService _assetEntryLocalService;

	@Inject
	private AssetVocabularyLocalService _assetVocabularyLocalService;

	@Inject
	private CompanyLocalService _companyLocalService;

	@DeleteAfterTestRun
	private Group _group;

	@Inject
	private JournalArticleLocalService _journalArticleLocalService;

	@Inject
	private ObjectEntryLocalService _objectEntryLocalService;

	@Inject
	private TranslationEntryLocalService _translationEntryLocalService;

	@Inject
	private TranslationManager _translationManager;

	@Inject
	private WorkflowDefinitionLinkLocalService
		_workflowDefinitionLinkLocalService;

	@Inject
	private WorkflowInstanceLinkLocalService _workflowInstanceLinkLocalService;

}