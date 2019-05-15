package spring.mine.result.controller;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.http.HttpServletRequest;

import org.apache.commons.beanutils.PropertyUtils;
import org.apache.commons.validator.GenericValidator;
import org.springframework.stereotype.Controller;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.Errors;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.ModelAndView;

import spring.mine.common.controller.BaseController;
import spring.mine.result.form.AccessionResultsForm;
import us.mn.state.health.lims.common.services.DisplayListService;
import us.mn.state.health.lims.common.services.StatusService.AnalysisStatus;
import us.mn.state.health.lims.common.util.ConfigurationProperties;
import us.mn.state.health.lims.common.util.ConfigurationProperties.Property;
import us.mn.state.health.lims.inventory.action.InventoryUtility;
import us.mn.state.health.lims.inventory.form.InventoryKitItem;
import us.mn.state.health.lims.login.dao.UserModuleService;
import us.mn.state.health.lims.login.daoimpl.UserModuleServiceImpl;
import us.mn.state.health.lims.patient.valueholder.Patient;
import us.mn.state.health.lims.result.action.util.ResultsLoadUtility;
import us.mn.state.health.lims.result.action.util.ResultsPaging;
import us.mn.state.health.lims.role.daoimpl.RoleDAOImpl;
import us.mn.state.health.lims.role.valueholder.Role;
import us.mn.state.health.lims.sample.dao.SampleDAO;
import us.mn.state.health.lims.sample.daoimpl.SampleDAOImpl;
import us.mn.state.health.lims.sample.valueholder.Sample;
import us.mn.state.health.lims.samplehuman.dao.SampleHumanDAO;
import us.mn.state.health.lims.samplehuman.daoimpl.SampleHumanDAOImpl;
import us.mn.state.health.lims.test.beanItems.TestResultItem;
import us.mn.state.health.lims.userrole.dao.UserRoleDAO;
import us.mn.state.health.lims.userrole.daoimpl.UserRoleDAOImpl;

@Controller
public class AccessionResultsController extends BaseController {

	private String accessionNumber;
	private Sample sample;
	private InventoryUtility inventoryUtility = new InventoryUtility();
	private static SampleDAO sampleDAO = new SampleDAOImpl();
	private static UserModuleService userModuleService = new UserModuleServiceImpl();
	private static String RESULT_EDIT_ROLE_ID;

	static {
		Role editRole = new RoleDAOImpl().getRoleByName("Results modifier");

		if (editRole != null) {
			RESULT_EDIT_ROLE_ID = editRole.getId();
		}
	}

	@RequestMapping(value = "/AccessionResults", method = RequestMethod.GET)
	public ModelAndView showAccessionResults(HttpServletRequest request)
			throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
		AccessionResultsForm form = new AccessionResultsForm();

		request.getSession().setAttribute(SAVE_DISABLED, TRUE);
		PropertyUtils.setProperty(form, "referralReasons",
				DisplayListService.getList(DisplayListService.ListType.REFERRAL_REASONS));
		PropertyUtils.setProperty(form, "rejectReasons",
				DisplayListService.getNumberedListWithLeadingBlank(DisplayListService.ListType.REJECTION_REASONS));

		ResultsPaging paging = new ResultsPaging();
		String newPage = request.getParameter("page");
		if (GenericValidator.isBlankOrNull(newPage)) {

			accessionNumber = request.getParameter("accessionNumber");
			PropertyUtils.setProperty(form, "displayTestKit", false);

			if (!GenericValidator.isBlankOrNull(accessionNumber)) {
				Errors errors = new BeanPropertyBindingResult(form, "form");
				ResultsLoadUtility resultsUtility = new ResultsLoadUtility(getSysUserId(request));
				// This is for Haiti_LNSP if it gets more complicated use the status set stuff
				resultsUtility.addExcludedAnalysisStatus(AnalysisStatus.Canceled);
				// resultsUtility.addExcludedAnalysisStatus(AnalysisStatus.Finalized);
				resultsUtility.setLockCurrentResults(modifyResultsRoleBased() && userNotInRole(request));
				validateAll(request, errors, form);

				if (errors.hasErrors()) {
					saveErrors(errors);
					request.setAttribute(ALLOW_EDITS_KEY, "false");

					setEmptyResults(form);

					return findForward(FWD_FAIL, form);
				}

				PropertyUtils.setProperty(form, "searchFinished", Boolean.TRUE);

				getSample();

				if (!GenericValidator.isBlankOrNull(sample.getId())) {
					Patient patient = getPatient();
					resultsUtility.addIdentifingPatientInfo(patient, form);

					List<TestResultItem> results = resultsUtility.getGroupedTestsForSample(sample, patient);

					if (resultsUtility.inventoryNeeded()) {
						addInventory(form);
						PropertyUtils.setProperty(form, "displayTestKit", true);
					} else {
						addEmptyInventoryList(form);
					}

					paging.setDatabaseResults(request, form, results);
				} else {
					setEmptyResults(form);
				}
			} else {
				PropertyUtils.setProperty(form, "testResult", new ArrayList<TestResultItem>());
				PropertyUtils.setProperty(form, "searchFinished", Boolean.FALSE);
			}
		} else {
			paging.page(request, form, newPage);
		}

		return findForward(FWD_SUCCESS, form);
	}

	private boolean modifyResultsRoleBased() {
		return "true"
				.equals(ConfigurationProperties.getInstance().getPropertyValue(Property.roleRequiredForModifyResults));
	}

	private boolean userNotInRole(HttpServletRequest request) {
		if (userModuleService.isUserAdmin(request)) {
			return false;
		}

		UserRoleDAO userRoleDAO = new UserRoleDAOImpl();

		List<String> roleIds = userRoleDAO.getRoleIdsForUser(getSysUserId(request));

		return !roleIds.contains(RESULT_EDIT_ROLE_ID);
	}

	private void setEmptyResults(AccessionResultsForm form)
			throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
		PropertyUtils.setProperty(form, "testResult", new ArrayList<TestResultItem>());
		PropertyUtils.setProperty(form, "displayTestKit", false);
		addEmptyInventoryList(form);
	}

	private void addInventory(AccessionResultsForm form)
			throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {

		List<InventoryKitItem> list = inventoryUtility.getExistingActiveInventory();
		List<String> hivKits = new ArrayList<>();
		List<String> syphilisKits = new ArrayList<>();
		for (InventoryKitItem item : list) {
			if (item.getType().equals("HIV")) {
				hivKits.add(item.getInventoryLocationId());
			} else {
				syphilisKits.add(item.getInventoryLocationId());
			}
		}
		PropertyUtils.setProperty(form, "hivKits", hivKits);
		PropertyUtils.setProperty(form, "syphilisKits", syphilisKits);
		PropertyUtils.setProperty(form, "inventoryItems", list);
	}

	private void addEmptyInventoryList(AccessionResultsForm form)
			throws IllegalAccessException, InvocationTargetException, NoSuchMethodException {
		PropertyUtils.setProperty(form, "inventoryItems", new ArrayList<InventoryKitItem>());
		PropertyUtils.setProperty(form, "hivKits", new ArrayList<String>());
		PropertyUtils.setProperty(form, "syphilisKits", new ArrayList<String>());
	}

	private Errors validateAll(HttpServletRequest request, Errors errors, AccessionResultsForm form) {

		Sample sample = sampleDAO.getSampleByAccessionNumber(accessionNumber);

		if (sample == null) {
			// ActionError error = new ActionError("sample.edit.sample.notFound",
			// accessionNumber, null, null);
			errors.reject("sample.edit.sample.notFound", new String[] { accessionNumber },
					"sample.edit.sample.notFound");
		}

		return errors;
	}

	private Patient getPatient() {
		SampleHumanDAO sampleHumanDAO = new SampleHumanDAOImpl();
		return sampleHumanDAO.getPatientForSample(sample);
	}

	private void getSample() {
		sample = sampleDAO.getSampleByAccessionNumber(accessionNumber);
	}

	@Override
	protected String findLocalForward(String forward) {
		if (FWD_SUCCESS.equals(forward)) {
			return "accessionResultDefinition";
		} else if (FWD_FAIL.equals(forward)) {
			return "accessionResultDefinition";
		} else {
			return "PageNotFound";
		}
	}

	@Override
	protected String getPageTitleKey() {
		return "banner.menu.results";
	}

	@Override
	protected String getPageSubtitleKey() {
		return "banner.menu.results";
	}
}
