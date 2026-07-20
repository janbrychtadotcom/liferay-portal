/**
 * SPDX-FileCopyrightText: (c) 2026 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

package com.liferay.object.internal.upgrade.v13_1_1;

import com.liferay.document.library.kernel.service.DLAppLocalService;
import com.liferay.friendly.url.model.FriendlyURLEntry;
import com.liferay.friendly.url.model.FriendlyURLEntryLocalization;
import com.liferay.friendly.url.service.FriendlyURLEntryLocalService;
import com.liferay.object.constants.ObjectFieldConstants;
import com.liferay.object.constants.ObjectFieldSettingConstants;
import com.liferay.object.definition.util.ObjectDefinitionUtil;
import com.liferay.object.field.setting.util.ObjectFieldSettingUtil;
import com.liferay.object.model.ObjectDefinition;
import com.liferay.object.model.ObjectEntry;
import com.liferay.object.model.ObjectField;
import com.liferay.object.service.ObjectDefinitionLocalService;
import com.liferay.object.service.ObjectEntryLocalService;
import com.liferay.object.service.persistence.ObjectFieldPersistence;
import com.liferay.petra.string.StringBundler;
import com.liferay.portal.kernel.dao.orm.ActionableDynamicQuery;
import com.liferay.portal.kernel.dao.orm.RestrictionsFactoryUtil;
import com.liferay.portal.kernel.exception.PortalException;
import com.liferay.portal.kernel.log.Log;
import com.liferay.portal.kernel.log.LogFactoryUtil;
import com.liferay.portal.kernel.repository.model.FileEntry;
import com.liferay.portal.kernel.service.ClassNameLocalService;
import com.liferay.portal.kernel.service.CompanyLocalService;
import com.liferay.portal.kernel.service.ServiceContext;
import com.liferay.portal.kernel.upgrade.UpgradeProcess;
import com.liferay.portal.kernel.util.GetterUtil;
import com.liferay.portal.kernel.workflow.WorkflowConstants;

import java.io.Serializable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Backfills the friendly URL of the raw {@link FileEntry} backing an
 * attachment field, for object entries (of any object definition, not just
 * CMS "Basic Document") created or updated before {@code
 * ObjectEntryLocalServiceImpl} started keeping the two in sync.
 *
 * @author Jan Brychta
 */
public class AttachmentFileEntryFriendlyURLUpgradeProcess
	extends UpgradeProcess {

	public AttachmentFileEntryFriendlyURLUpgradeProcess(
		ClassNameLocalService classNameLocalService,
		CompanyLocalService companyLocalService,
		DLAppLocalService dlAppLocalService,
		FriendlyURLEntryLocalService friendlyURLEntryLocalService,
		ObjectDefinitionLocalService objectDefinitionLocalService,
		ObjectEntryLocalService objectEntryLocalService,
		ObjectFieldPersistence objectFieldPersistence) {

		_classNameLocalService = classNameLocalService;
		_companyLocalService = companyLocalService;
		_dlAppLocalService = dlAppLocalService;
		_friendlyURLEntryLocalService = friendlyURLEntryLocalService;
		_objectDefinitionLocalService = objectDefinitionLocalService;
		_objectEntryLocalService = objectEntryLocalService;
		_objectFieldPersistence = objectFieldPersistence;
	}

	@Override
	protected void doUpgrade() throws Exception {
		_companyLocalService.forEachCompanyId(this::_upgradeCompany);
	}

	private List<ObjectField> _getAttachmentObjectFields(
		long objectDefinitionId) {

		List<ObjectField> attachmentObjectFields = new ArrayList<>();

		for (ObjectField objectField :
				_objectFieldPersistence.findByObjectDefinitionId(
					objectDefinitionId)) {

			if (objectField.compareBusinessType(
					ObjectFieldConstants.BUSINESS_TYPE_ATTACHMENT) &&
				Objects.equals(
					ObjectFieldSettingUtil.getValue(
						ObjectFieldSettingConstants.NAME_FILE_SOURCE,
						objectField),
					ObjectFieldSettingConstants.
						VALUE_USER_COMPUTER_TO_DOCS_AND_MEDIA)) {

				attachmentObjectFields.add(objectField);
			}
		}

		return attachmentObjectFields;
	}

	private boolean _isInSync(
		long fileEntryClassNameId, FileEntry fileEntry,
		Map<String, String> urlTitleMap) {

		FriendlyURLEntry fileEntryFriendlyURLEntry =
			_friendlyURLEntryLocalService.fetchMainFriendlyURLEntry(
				fileEntryClassNameId, fileEntry.getFileEntryId());

		if (fileEntryFriendlyURLEntry == null) {
			return false;
		}

		Map<String, String> fileEntryUrlTitleMap = new HashMap<>();

		for (FriendlyURLEntryLocalization friendlyURLEntryLocalization :
				_friendlyURLEntryLocalService.getFriendlyURLEntryLocalizations(
					fileEntryFriendlyURLEntry.getFriendlyURLEntryId())) {

			fileEntryUrlTitleMap.put(
				friendlyURLEntryLocalization.getLanguageId(),
				friendlyURLEntryLocalization.getUrlTitle());
		}

		return fileEntryUrlTitleMap.equals(urlTitleMap);
	}

	private boolean _syncFileEntry(
			long fileEntryClassNameId, long dlFileEntryId,
			String defaultLanguageId, Map<String, String> urlTitleMap)
		throws PortalException {

		if (dlFileEntryId <= 0) {
			return false;
		}

		FileEntry fileEntry;

		try {
			fileEntry = _dlAppLocalService.getFileEntry(dlFileEntryId);
		}
		catch (PortalException portalException) {
			if (_log.isDebugEnabled()) {
				_log.debug(portalException);
			}

			return false;
		}

		if (_isInSync(fileEntryClassNameId, fileEntry, urlTitleMap)) {
			return false;
		}

		_friendlyURLEntryLocalService.addFriendlyURLEntry(
			fileEntry.getGroupId(), fileEntryClassNameId,
			fileEntry.getFileEntryId(), defaultLanguageId, urlTitleMap,
			new ServiceContext());

		return true;
	}

	private void _upgradeCompany(long companyId) throws PortalException {
		long fileEntryClassNameId = _classNameLocalService.getClassNameId(
			FileEntry.class);

		for (ObjectDefinition objectDefinition :
				_objectDefinitionLocalService.getObjectDefinitions(
					companyId, WorkflowConstants.STATUS_APPROVED)) {

			if (ObjectDefinitionUtil.isDefaultFriendlyURLSeparator(
					objectDefinition.getFriendlyURLSeparator())) {

				continue;
			}

			List<ObjectField> attachmentObjectFields =
				_getAttachmentObjectFields(
					objectDefinition.getObjectDefinitionId());

			if (attachmentObjectFields.isEmpty()) {
				continue;
			}

			_upgradeObjectDefinition(
				companyId, fileEntryClassNameId, objectDefinition,
				attachmentObjectFields);
		}
	}

	private void _upgradeObjectDefinition(
			long companyId, long fileEntryClassNameId,
			ObjectDefinition objectDefinition,
			List<ObjectField> attachmentObjectFields)
		throws PortalException {

		long objectEntryClassNameId = _classNameLocalService.getClassNameId(
			objectDefinition.getClassName());

		AtomicInteger syncedCount = new AtomicInteger();

		ActionableDynamicQuery actionableDynamicQuery =
			_objectEntryLocalService.getActionableDynamicQuery();

		actionableDynamicQuery.setAddCriteriaMethod(
			dynamicQuery -> dynamicQuery.add(
				RestrictionsFactoryUtil.eq(
					"objectDefinitionId",
					objectDefinition.getObjectDefinitionId())));
		actionableDynamicQuery.setCompanyId(companyId);
		actionableDynamicQuery.setPerformActionMethod(
			(ObjectEntry objectEntry) -> {
				try {
					if (_upgradeObjectEntry(
							fileEntryClassNameId, objectEntryClassNameId,
							objectEntry, attachmentObjectFields)) {

						syncedCount.incrementAndGet();
					}
				}
				catch (PortalException portalException) {
					if (_log.isWarnEnabled()) {
						_log.warn(
							StringBundler.concat(
								"Unable to sync the friendly URL of a file ",
								"entry attached to object entry ",
								objectEntry.getObjectEntryId(), " for company ",
								companyId),
							portalException);
					}
				}
			});

		actionableDynamicQuery.performActions();

		if (_log.isInfoEnabled() && (syncedCount.get() > 0)) {
			_log.info(
				StringBundler.concat(
					"Synced the friendly URL of ", syncedCount.get(),
					" file entries attached to object entries of object ",
					"definition ", objectDefinition.getObjectDefinitionId()));
		}
	}

	private boolean _upgradeObjectEntry(
			long fileEntryClassNameId, long objectEntryClassNameId,
			ObjectEntry objectEntry, List<ObjectField> attachmentObjectFields)
		throws PortalException {

		FriendlyURLEntry friendlyURLEntry =
			_friendlyURLEntryLocalService.fetchMainFriendlyURLEntry(
				objectEntryClassNameId, objectEntry.getObjectEntryId());

		if (friendlyURLEntry == null) {
			return false;
		}

		Map<String, String> urlTitleMap = new HashMap<>();

		for (FriendlyURLEntryLocalization friendlyURLEntryLocalization :
				_friendlyURLEntryLocalService.getFriendlyURLEntryLocalizations(
					friendlyURLEntry.getFriendlyURLEntryId())) {

			urlTitleMap.put(
				friendlyURLEntryLocalization.getLanguageId(),
				friendlyURLEntryLocalization.getUrlTitle());
		}

		if (urlTitleMap.isEmpty()) {
			return false;
		}

		Map<String, Serializable> values = objectEntry.getValues();

		boolean synced = false;

		for (ObjectField objectField : attachmentObjectFields) {
			if (objectField.isLocalized()) {
				Map<String, Serializable> localizedValues =
					(Map<String, Serializable>)values.get(
						objectField.getI18nObjectFieldName());

				if (localizedValues == null) {
					continue;
				}

				for (Map.Entry<String, String> urlTitleEntry :
						urlTitleMap.entrySet()) {

					String languageId = urlTitleEntry.getKey();

					if (_syncFileEntry(
							fileEntryClassNameId,
							GetterUtil.getLong(localizedValues.get(languageId)),
							languageId,
							Collections.singletonMap(
								languageId, urlTitleEntry.getValue()))) {

						synced = true;
					}
				}
			}
			else if (_syncFileEntry(
						fileEntryClassNameId,
						GetterUtil.getLong(values.get(objectField.getName())),
						friendlyURLEntry.getDefaultLanguageId(), urlTitleMap)) {

				synced = true;
			}
		}

		return synced;
	}

	private static final Log _log = LogFactoryUtil.getLog(
		AttachmentFileEntryFriendlyURLUpgradeProcess.class);

	private final ClassNameLocalService _classNameLocalService;
	private final CompanyLocalService _companyLocalService;
	private final DLAppLocalService _dlAppLocalService;
	private final FriendlyURLEntryLocalService _friendlyURLEntryLocalService;
	private final ObjectDefinitionLocalService _objectDefinitionLocalService;
	private final ObjectEntryLocalService _objectEntryLocalService;
	private final ObjectFieldPersistence _objectFieldPersistence;

}