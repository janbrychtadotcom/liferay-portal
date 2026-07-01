/**
 * SPDX-FileCopyrightText: (c) 2025 Liferay, Inc. https://liferay.com
 * SPDX-License-Identifier: LGPL-2.1-or-later OR LicenseRef-Liferay-DXP-EULA-2.0.0-2023-06
 */

import {
	ObjectDefinition,
	ObjectDefinitionAPI,
} from '@liferay/object-admin-rest-client-js';
import {expect, mergeTests} from '@playwright/test';

import {dataApiHelpersTest} from '../../../fixtures/dataApiHelpersTest';
import {displayPageTemplatesPagesTest} from '../../../fixtures/displayPageTemplatesPagesTest';
import {isolatedSiteTest} from '../../../fixtures/isolatedSiteTest';
import {loginTest} from '../../../fixtures/loginTest';
import {pageEditorPagesTest} from '../../../fixtures/pageEditorPagesTest';
import {getRandomInt} from '../../../utils/getRandomInt';
import getRandomString from '../../../utils/getRandomString';

const test = mergeTests(
	dataApiHelpersTest,
	displayPageTemplatesPagesTest,
	isolatedSiteTest,
	loginTest(),
	pageEditorPagesTest
);

test(
	'Mapped object entry field values are visible to guest users on a display page',
	{tag: '@LPD-96215'},
	async ({
		apiHelpers,
		displayPageTemplatesPage,
		page,
		pageEditorPage,
		site,
	}) => {

		// Create object definition with a localized field

		const objectDefinitionAPIClient =
			await apiHelpers.buildRestClient(ObjectDefinitionAPI);

		const objectName = `Sample${getRandomInt()}`;

		const {body: objectDefinition} =
			await objectDefinitionAPIClient.postObjectDefinition({
				label: {en_US: objectName},
				name: objectName,
				objectFields: [
					{
						DBType: 'String',
						businessType: 'Text',
						indexed: true,
						indexedAsKeyword: false,
						indexedLanguageId: 'en_US',
						label: {en_US: 'Title'},
						localized: true,
						name: 'title',
						objectFieldSettings: [],
						required: true,
					},
				] as ObjectDefinition['objectFields'],
				pluralLabel: {en_US: `${objectName}s`},
				scope: 'company',
				status: {code: 0},
			} as ObjectDefinition);

		apiHelpers.data.push({
			id: objectDefinition.id,
			type: 'objectDefinition',
		});

		// Create entry with en_US only

		const applicationName = `c/${objectName.toLowerCase()}s`;

		const entry = await apiHelpers.objectEntry.postObjectEntry(
			{title_i18n: {en_US: 'English Title'}},
			applicationName
		);

		// Get the classNameId required for the DPT URL and creation

		const className =
			await apiHelpers.jsonWebServicesClassName.fetchClassName(
				objectDefinition.className
			);

		// Create a display page template for the object type

		const displayPageTemplateName = getRandomString();

		await apiHelpers.jsonWebServicesLayoutPageTemplateEntry.addDisplayPageLayoutPageTemplateEntry(
			{
				classNameId: className.classNameId,
				groupId: site.id,
				name: displayPageTemplateName,
			}
		);

		// Open the DPT editor and map a Heading fragment to the Title field

		await displayPageTemplatesPage.goto(site.friendlyUrlPath);

		await displayPageTemplatesPage.editTemplate(displayPageTemplateName);

		await pageEditorPage.addFragment('Basic Components', 'Heading');

		const headingId = await pageEditorPage.getFragmentId('Heading');

		await pageEditorPage.selectEditable(headingId, 'element-text');

		await page.getByLabel('Field').selectOption('Title');

		await pageEditorPage.waitForChangesSaved();

		// Publish the display page template

		await displayPageTemplatesPage.publishTemplate();

		// Navigate to the display page as a guest (no authentication)

		await page.context().clearCookies();

		await page.goto(
			`/web${site.friendlyUrlPath}/e/${displayPageTemplateName}/${className.classNameId}/${entry.id}`
		);

		// The field value must be visible even without an authenticated session

		await expect(
			page.getByRole('heading', {name: 'English Title'})
		).toBeVisible();
	}
);
