/**
 *  Copyright 2010 Society for Health Information Systems Programmes, India (HISP India)
 *
 *  This file is part of Hospital-core module.
 *
 *  Hospital-core module is free software: you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation, either version 3 of the License, or
 *  (at your option) any later version.

 *  Hospital-core module is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with Hospital-core module.  If not, see <http://www.gnu.org/licenses/>.
 *
 **/

package org.openmrs.module.hospitalcore.db.hibernate;

import java.math.BigInteger;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Vector;

import javax.sql.DataSource;

import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang.StringUtils;
import org.hibernate.Criteria;
import org.hibernate.Query;
import org.hibernate.SessionFactory;
import org.hibernate.classic.Session;
import org.hibernate.criterion.Order;
import org.hibernate.criterion.Restrictions;
import org.openmrs.Concept;
import org.openmrs.Encounter;
import org.openmrs.EncounterType;
import org.openmrs.Obs;
import org.openmrs.Patient;
import org.openmrs.PatientIdentifier;
import org.openmrs.Person;
import org.openmrs.PersonAddress;
import org.openmrs.PersonAttribute;
import org.openmrs.PersonAttributeType;
import org.openmrs.PersonName;
import org.openmrs.api.context.Context;
import org.openmrs.api.db.DAOException;
import org.openmrs.module.hospitalcore.HospitalCoreService;
import org.openmrs.module.hospitalcore.concept.ConceptModel;
import org.openmrs.module.hospitalcore.concept.Mapping;
import org.openmrs.module.hospitalcore.db.HospitalCoreDAO;
import org.openmrs.module.hospitalcore.model.CoreForm;
import org.openmrs.module.hospitalcore.model.IpdPatientAdmitted;
import org.openmrs.module.hospitalcore.model.PatientSearch;
import org.openmrs.module.hospitalcore.util.DateUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

public class HibernateHospitalCoreDAO implements HospitalCoreDAO {

	SimpleDateFormat formatter = new SimpleDateFormat("dd/MM/yyyy HH:mm:ss");

	SimpleDateFormat formatterExt = new SimpleDateFormat("dd/MM/yyyy");

	private SessionFactory sessionFactory;
	
	@Autowired
	private DataSource dataSource;

	public SessionFactory getSessionFactory() {
		return sessionFactory;
	}

	public void setSessionFactory(SessionFactory sessionFactory) {
		this.sessionFactory = sessionFactory;
	}

	public List<Obs> listObsGroup(Integer personId, Integer conceptId,
			Integer min, Integer max) throws DAOException {
		Criteria criteria = sessionFactory.getCurrentSession()
				.createCriteria(Obs.class, "obs")
				.add(Restrictions.eq("obs.person.personId", personId))
				.add(Restrictions.eq("obs.concept.conceptId", conceptId))
				.add(Restrictions.isNull("obs.obsGroup"))
				.addOrder(Order.desc("obs.dateCreated"));
		if (max > 0) {
			criteria.setFirstResult(min).setMaxResults(max);
		}
		List<Obs> list = criteria.list();
		return list;
	}

	public Obs getObsGroupCurrentDate(Integer personId, Integer conceptId)
			throws DAOException {
		Criteria criteria = sessionFactory.getCurrentSession()
				.createCriteria(Obs.class, "obs")
				.add(Restrictions.eq("obs.person.personId", personId))
				.add(Restrictions.eq("obs.concept.conceptId", conceptId))
				.add(Restrictions.isNull("obs.obsGroup"));
		String date = formatterExt.format(new Date());
		String startFromDate = date + " 00:00:00";
		String endFromDate = date + " 23:59:59";
		try {
			criteria.add(Restrictions.and(
					Restrictions.ge("obs.dateCreated",
							formatter.parse(startFromDate)),
					Restrictions.le("obs.dateCreated",
							formatter.parse(endFromDate))));
		} catch (Exception e) {
			// TODO: handle exception
			System.out.println("Error convert date: " + e.toString());
			e.printStackTrace();
		}

		List<Obs> list = criteria.list();
		return CollectionUtils.isNotEmpty(list) ? list.get(0) : null;
	}

	public Integer buildConcepts(List<ConceptModel> conceptModels) {

		HospitalCoreService hcs = Context.getService(HospitalCoreService.class);
		Session session = sessionFactory.getCurrentSession();
		Integer diagnosisNo = 0;
		// Transaction tx = session.beginTransaction();
		// tx.begin();
		for (int i = 0; i < conceptModels.size(); i++) {
			ConceptModel conceptModel = conceptModels.get(i);
			Concept concept = hcs.insertConcept(
					conceptModel.getConceptDatatype(),
					conceptModel.getConceptClass(), conceptModel.getName(), "",
					conceptModel.getDescription());
			System.out.println("concept ==> " + concept.getId());
			for (String synonym : conceptModel.getSynonyms()) {
				hcs.insertSynonym(concept, synonym);
			}

			for (Mapping mapping : conceptModel.getMappings()) {
				hcs.insertMapping(concept, mapping.getSource(),
						mapping.getSourceCode());
			}

			if (i % 20 == 0) {
				session.flush();
				session.clear();
				System.out.println("Imported " + (i + 1) + " diagnosis ("
						+ (i / conceptModels.size() * 100) + "%)");
			}
			diagnosisNo++;
		}
		return diagnosisNo;
		// tx.commit();
	}

	public List<Patient> searchPatient(String nameOrIdentifier, String gender,
			int age, int rangeAge, String date, int rangeDay,
			String relativeName) throws DAOException {
		List<Patient> patients = new Vector<Patient>();

		String hql = "SELECT DISTINCT p.patient_id,pi.identifier,pn.given_name ,pn.middle_name ,pn.family_name ,ps.gender,ps.birthdate ,EXTRACT(YEAR FROM (FROM_DAYS(DATEDIFF(NOW(),ps.birthdate)))) age,pn.person_name_id FROM patient p "
				+ "INNER JOIN person ps ON p.patient_id = ps.person_id "
				+ "INNER JOIN patient_identifier pi ON p.patient_id = pi.patient_id "
				+ "INNER JOIN person_name pn ON p.patient_id = pn.person_id "
				+ "INNER JOIN person_attribute pa ON p.patient_id= pa.person_id "
				+ "INNER JOIN person_attribute_type pat ON pa.person_attribute_type_id = pat.person_attribute_type_id "
				+ "WHERE (pi.identifier like '%"
				+ nameOrIdentifier
				+ "%' "
				+ "OR pn.given_name like '"
				+ nameOrIdentifier
				+ "%' "
				+ "OR pn.middle_name like '"
				+ nameOrIdentifier
				+ "%' "
				+ "OR pn.family_name like '" + nameOrIdentifier + "%') ";
		if (StringUtils.isNotBlank(gender)) {
			hql += " AND ps.gender = '" + gender + "' ";
		}
		if (StringUtils.isNotBlank(relativeName)) {
			hql += " AND pat.name = 'Father/Husband Name' AND pa.value like '"
					+ relativeName + "' ";
		}
		if (StringUtils.isNotBlank(date)) {
			String startDate = DateUtils.getDateFromRange(date, -rangeDay)
					+ " 00:00:00";
			String endtDate = DateUtils.getDateFromRange(date, rangeDay)
					+ " 23:59:59";
			hql += " AND ps.birthdate BETWEEN '" + startDate + "' AND '"
					+ endtDate + "' ";
		}
		if (age > 0) {
			hql += " AND EXTRACT(YEAR FROM (FROM_DAYS(DATEDIFF(NOW(),ps.birthdate)))) >="
					+ (age - rangeAge)
					+ " AND EXTRACT(YEAR FROM (FROM_DAYS(DATEDIFF(NOW(),ps.birthdate)))) <= "
					+ (age + rangeAge) + " ";
		}
		hql += " ORDER BY p.patient_id ASC";

		Query query = sessionFactory.getCurrentSession().createSQLQuery(hql);
		List l = query.list();
		if (CollectionUtils.isNotEmpty(l))
			for (Object obj : l) {
				Object[] obss = (Object[]) obj;
				if (obss != null && obss.length > 0) {
					Person person = new Person((Integer) obss[0]);
					PersonName personName = new PersonName((Integer) obss[8]);
					personName.setGivenName((String) obss[2]);
					personName.setMiddleName((String) obss[3]);
					personName.setFamilyName((String) obss[4]);
					personName.setPerson(person);
					Set<PersonName> names = new HashSet<PersonName>();
					names.add(personName);
					person.setNames(names);
					Patient patient = new Patient(person);
					PatientIdentifier patientIdentifier = new PatientIdentifier();
					patientIdentifier.setPatient(patient);
					patientIdentifier.setIdentifier((String) obss[1]);
					Set<PatientIdentifier> identifier = new HashSet<PatientIdentifier>();
					identifier.add(patientIdentifier);
					patient.setIdentifiers(identifier);
					patient.setGender((String) obss[5]);
					patient.setBirthdate((Date) obss[6]);
					patients.add(patient);
				}

			}
		return patients;
	}

	@SuppressWarnings("rawtypes")
	public List<Patient> searchPatient(String hql) {
		List<Patient> patients = new Vector<Patient>();
		Query query = sessionFactory.getCurrentSession().createSQLQuery(hql);
		List list = query.list();
		if (CollectionUtils.isNotEmpty(list))
			for (Object obj : list) {
				Object[] obss = (Object[]) obj;
				if (obss != null && obss.length > 0) {
					//for address in display
					PersonAddress personadd = new PersonAddress();
					personadd.setAddress1((String)obss[11]);
					personadd.setCityVillage((String)obss[12]);
					Person person = new Person((Integer) obss[0]);
					PersonName personName = new PersonName((Integer) obss[8]);
					personName.setGivenName((String) obss[2]);
					personName.setMiddleName((String) obss[3]);
					personName.setFamilyName((String) obss[4]);
					personName.setPerson(person);
					Set<PersonName> names = new HashSet<PersonName>();
					names.add(personName);
					person.setNames(names);
					Set<PersonAddress> addres = new HashSet<PersonAddress>();
					addres.add(personadd);
					person.setAddresses(addres);
					Patient patient = new Patient(person);
					PatientIdentifier patientIdentifier = new PatientIdentifier();
					patientIdentifier.setPatient(patient);
					patientIdentifier.setIdentifier((String) obss[1]);
					Set<PatientIdentifier> identifier = new HashSet<PatientIdentifier>();
					identifier.add(patientIdentifier);
					patient.setIdentifiers(identifier);
					patient.setGender((String) obss[5]);
					patient.setBirthdate((Date) obss[6]);
					// ghanshyam,22-oct-2013,New Requirement #2940 Dealing with
					// dead patient
					if(obss.length > 9){
					if (obss[9] != null) {
						if (obss[9].toString().equals("1")) {
							patient.setDead(true);
						} else if (obss[9].toString().equals("0")) {
							patient.setDead(false);
						}
					}
					}
					if(obss.length > 10){
					if (obss[10] != null) {
						if (obss[10].toString().equals("1")) {
							patient.setVoided(true);
						} else if (obss[10].toString().equals("0")) {
							patient.setVoided(false);
						}
					}
					}
					patients.add(patient);
				}
			}
		return patients;
	}

	@SuppressWarnings("rawtypes")
	public BigInteger getPatientSearchResultCount(String hql) {
		BigInteger count = new BigInteger("0");
		Query query = sessionFactory.getCurrentSession().createSQLQuery(hql);
		List list = query.list();
		if (CollectionUtils.isNotEmpty(list)) {
			count = (BigInteger) list.get(0);
		}
		return count;
	}

	@SuppressWarnings("rawtypes")
	public List<PersonAttribute> getPersonAttributes(Integer patientId) {
		List<PersonAttribute> attributes = new ArrayList<PersonAttribute>();
		String hql = "SELECT pa.person_attribute_type_id, pa.`value` ,pa.person_attribute_id FROM person_attribute pa WHERE pa.person_id = "
				+ patientId + " AND pa.voided = 0;";
		Query query = sessionFactory.getCurrentSession().createSQLQuery(hql);
		List l = query.list();
		if (CollectionUtils.isNotEmpty(l)) {
			for (Object obj : l) {
				Object[] obss = (Object[]) obj;
				if (obss != null && obss.length > 0) {
					PersonAttribute attribute = new PersonAttribute();
					PersonAttributeType type = new PersonAttributeType(
							(Integer) obss[0]);
					attribute.setAttributeType(type);
					attribute.setValue((String) obss[1]);
					attribute.setPersonAttributeId((Integer) obss[2]);
					attributes.add(attribute);
				}
			}
		}

		return attributes;
	}

	public Encounter getLastVisitEncounter(Patient patient,
			List<EncounterType> types) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(
				Encounter.class);
		criteria.add(Restrictions.eq("patient", patient));
		criteria.add(Restrictions.in("encounterType", types));
		criteria.addOrder(Order.desc("encounterDatetime"));
		criteria.setFirstResult(0);
		criteria.setMaxResults(1);
		return (Encounter) criteria.uniqueResult();
	}

	//
	// CORE FORM
	//
	public CoreForm saveCoreForm(CoreForm form) {
		return (CoreForm) sessionFactory.getCurrentSession().merge(form);
	}

	public CoreForm getCoreForm(Integer id) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(
				CoreForm.class);
		criteria.add(Restrictions.eq("id", id));
		return (CoreForm) criteria.uniqueResult();
	}

	@SuppressWarnings("unchecked")
	public List<CoreForm> getCoreForms(String conceptName) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(
				CoreForm.class);
		criteria.add(Restrictions.eq("conceptName", conceptName));
		return criteria.list();
	}

	@SuppressWarnings("unchecked")
	public List<CoreForm> getCoreForms() {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(
				CoreForm.class);
		return criteria.list();
	}

	public void deleteCoreForm(CoreForm form) {
		sessionFactory.getCurrentSession().delete(form);
	}

	//
	// PATIENT_SEARCH
	//
	public PatientSearch savePatientSearch(PatientSearch patientSearch) {
		return (PatientSearch) sessionFactory.getCurrentSession().merge(
				patientSearch);
	}

	/**
	 * @see org.openmrs.module.hospitalcore.db.HospitalCoreDAO#getLastVisitTime(int)
	 */
	public java.util.Date getLastVisitTime(Patient patient) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(
				Encounter.class);
		Encounter encounter = new Encounter();
		criteria.add(Restrictions.eq("patient", patient));

		// Don't trust in system hour so we use encounterId (auto increase)
		criteria.addOrder(Order.desc("encounterId"));

		// return 1 last row
		criteria.setFirstResult(0); // read the first row (desc reading)
		criteria.setMaxResults(1); // return 1 row

		encounter = (Encounter) criteria.uniqueResult();
		return (java.util.Date) (encounter == null ? null : encounter
				.getEncounterDatetime());
	}

	// ghanshyam,22-oct-2013,New Requirement #2940 Dealing with dead patient
	public PatientSearch getPatient(int patientID) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(
				PatientSearch.class);
		criteria.add(Restrictions.eq("patientId", patientID));
		return (PatientSearch) criteria.uniqueResult();
	}

	/*public List<Patient> getAllEncounterCurrentDate(String date,
			Set<EncounterType> encounterTypes) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(
				Encounter.class);

		String startFromDate = date + " 00:00:00";
		;
		String endFromDate = date + " 23:59:59";
		try {
			criteria.add(Restrictions.and(
					Restrictions.ge("encounterDatetime",
							formatter.parse(startFromDate)),
					Restrictions.le("encounterDatetime",
							formatter.parse(endFromDate))));
			criteria.add(Restrictions.in("encounterType", encounterTypes));
		} catch (ParseException e) {
			e.printStackTrace();
		}
		List<Encounter> enc = criteria.list();
		List<Patient> dops = new ArrayList<Patient>();
		for (Encounter o : enc) {
			Patient p = Context.getPatientService()
					.getPatient(o.getPatientId());
			dops.add(p);
		}
		return dops;

	}*/

	public Set<Encounter> getEncountersByPatientAndDate(String date,
			Set<EncounterType> encounterTypes) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(
				Encounter.class);
		String startFromDate = date + " 00:00:00";
		;
		String endFromDate = date + " 23:59:59";
		try {
			criteria.add(Restrictions.and(
					Restrictions.ge("encounterDatetime",
							formatter.parse(startFromDate)),
					Restrictions.le("encounterDatetime",
							formatter.parse(endFromDate))));
			criteria.add(Restrictions.in("encounterType", encounterTypes));
		} catch (ParseException e) {
			e.printStackTrace();
		}
		List<Encounter> enc = criteria.list();
		Set<Encounter> dops = new LinkedHashSet<Encounter>();
		for (Encounter o : enc) {
			dops.add(o);

		}
		return dops;
	}

	public Set<Encounter> getEncountersByPatientAndDateFromObs(String date) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(
				Obs.class);

		String startFromDate = date + " 00:00:00";
		;
		String endFromDate = date + " 23:59:59";

		try {
			criteria.add(Restrictions.and(Restrictions.ge("obsDatetime",
					formatter.parse(startFromDate)), Restrictions.le(
					"obsDatetime", formatter.parse(endFromDate))));
		} catch (ParseException e) {
			e.printStackTrace();
		}
		List<Obs> enc = criteria.list();
		Set<Encounter> dops = new LinkedHashSet<Encounter>();
		for (Obs o : enc) {

			if (o.getEncounter() != null
					&& o.getEncounter().getEncounterType().getName()
							.equals("IPDENCOUNTER")) {
				dops.add(o.getEncounter());
			}

		}
		return dops;
	}

	public List<Obs> getObsInstanceForDiagnosis(Encounter encounter,
			Concept concept) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(
				Obs.class);
		criteria.add(Restrictions.eq("encounter", encounter));
		criteria.add(Restrictions.eq("concept", concept));
		return criteria.list();
	}
	public PatientSearch getPatientByPatientId(int patientId) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(PatientSearch.class);
		criteria.add(Restrictions.eq("patientId", patientId));
		return (PatientSearch) criteria.uniqueResult();
	}
	public String getPatientType(Patient patientId) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(IpdPatientAdmitted.class);
		criteria.add(Restrictions.eq("patient", patientId));
		criteria.list();
		if(criteria.list().size()>0)
		{
			return "ipdPatient";
		}
		else
		{
			return "opdPatient"; 
		}
	}
	
	public PersonAttribute getPersonAttribute(Person person,PersonAttributeType personAttributeType){
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(PersonAttribute.class);
		criteria.add(Restrictions.eq("attributeType", personAttributeType));
		criteria.add(Restrictions.eq("voided", false));
		criteria.add(Restrictions.eq("person", person));
		return (PersonAttribute) criteria.uniqueResult();
	}
	
	/*
	public PersonAttribute getPersonAttribute(Person person,Integer personAttributeTypeId){
	String hql = "SELECT pa FROM person_attribute pa WHERE pa.person_id = " + person.getPersonId() + " AND pa.person_attribute_type_id = "+ personAttributeTypeId +" AND pa.voided = 0;";
	Query query = sessionFactory.getCurrentSession().createSQLQuery(hql);
		return (PersonAttribute) query.uniqueResult();
	}*/
	
	public void saveOrUpdatePersonAttribute(PersonAttribute personAttribute)
	throws DAOException {
    sessionFactory.getCurrentSession().saveOrUpdate(personAttribute);
    }
	
	public void saveOrUpdatePersonAttributee(Integer personAttributeId,Integer voidedBy)
	throws DAOException {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		String query="UPDATE person_attribute SET voided=1,voided_by="+voidedBy+",date_voided=NOW() WHERE person_attribute_id="+personAttributeId+";";
		jdbcTemplate.execute(query);
    }
	
	public Obs getObsByEncounterAndConcept(Encounter encounter,
			Set<Concept> concepts) {
		Criteria criteria = sessionFactory.getCurrentSession().createCriteria(
				Obs.class);
		criteria.add(Restrictions.eq("encounter", encounter));
		criteria.add(Restrictions.in("concept", concepts));
		return (Obs) criteria.uniqueResult();
	}
	
	public void saveOrUpdateObs(Obs obs) throws DAOException {
    sessionFactory.getCurrentSession().saveOrUpdate(obs);
	}
	
	// Clinical Morbidity Report
	public List<Map<String, Object>> getNumberOfPatientsWithAgeGroups(String valueCodes, Integer month, Integer year, String ward) {
		JdbcTemplate jdbcTemplate = new JdbcTemplate(dataSource);
		Integer wardEncounter = 9;
		String equal = "!=";
		if (ward.equals("IPD WARD")) {
			wardEncounter = 10;
		}

		if (ward.equals("OUTREACH OPD")) {
			equal = "=";
		}

		// encounter_type = 5 = REGINITIAL
		// encounter_type = 6 = REGREVISIT
		// encounter_type = 9 = OPDENCOUNTER
		// encounter_type = 10 = IPDENCOUNTER
		// concept_id = 3 = OPD WARD
		// concept_id = 3763 = OUTREACH IN FIELD OPD
		
		String query = "SELECT ageGroup, (SUM(IF(gender = 'M' AND encounter_type = 5, 1, 0))) AS \"NewMale\", (SUM(IF(gender = 'F' AND encounter_type = 5, 1, 0))) AS \"NewFemale\", (SUM(IF(gender = 'M' AND encounter_type = 6, 1, 0))) AS \"ReMale\", (SUM(IF(gender = 'F' AND encounter_type = 6, 1, 0))) AS \"ReFemale\" FROM (SELECT temp.encounter_id, CASE WHEN temp.ageInMonths <= 0 THEN '0-29 Days (Neonates)' WHEN temp.ageInMonths >= 1 AND temp.ageInMonths <= 11 THEN 'Infants' WHEN temp.ageInYears >= 1 AND temp.ageInYears < 5 THEN 'Under 5' WHEN temp.ageInYears >= 5 AND temp.ageInYears < 11 THEN '5 - 11 Years' WHEN temp.ageInYears >= 11 AND temp.ageInYears < 18 THEN '11 - 18 Years' WHEN temp.ageInYears >= 18 AND temp.ageInYears < 58 THEN '18 - 58 Years' WHEN temp.ageInYears >= 58 THEN '58 And Above' END AS \"ageGroup\", gender, encounter_type FROM (SELECT encounter.encounter_id, encounter.encounter_type, person.gender, TIMESTAMPDIFF(year, person.birthdate, encounter.encounter_datetime ) AS ageInYears, abs(TIMESTAMPDIFF(month, person.birthdate, encounter.encounter_datetime )) AS ageInMonths, ABS(TIMESTAMPDIFF(day, person.birthdate, encounter.encounter_datetime )) AS ageIndays FROM encounter INNER JOIN patient ON patient.patient_id = encounter.patient_id INNER JOIN person ON person.person_id = patient.patient_id INNER JOIN encounter encounter2 ON encounter2.patient_id = encounter.patient_id AND encounter2.encounter_type = " + wardEncounter + " INNER JOIN obs ON obs.encounter_id = encounter.encounter_id INNER JOIN concept_name cn ON cn.concept_id = obs.concept_id AND cn.concept_name_type = 'FULLY_SPECIFIED' AND cn.locale = 'en' AND cn.concept_id = 3 INNER JOIN concept_name cn2 ON cn2.concept_id = obs.value_coded AND cn2.concept_name_type = 'FULLY_SPECIFIED' AND cn2.locale = 'en' AND cn2.concept_id " + equal + " 3763 INNER JOIN obs obs2 ON obs2.encounter_id = encounter2.encounter_id INNER JOIN concept_name cn3 ON cn3.concept_id = obs2.concept_id AND cn3.concept_name_type = 'FULLY_SPECIFIED' AND cn3.locale = 'en' AND cn3.concept_id IN (2304, 2978) INNER JOIN concept_name cn4 ON cn4.concept_id = obs2.value_coded AND cn4.concept_name_type = 'FULLY_SPECIFIED' AND cn4.locale = 'en' AND cn4.concept_id IN ("+ valueCodes + ") WHERE encounter.encounter_type in (5, 6) AND MONTH(obs.obs_datetime) = " + month + " AND YEAR(obs.obs_datetime) = " + year + "  AND MONTH(obs2.obs_datetime) = " + month + " AND YEAR(obs2.obs_datetime) = " + year + " GROUP BY encounter.encounter_id ORDER BY encounter.encounter_datetime, encounter2.encounter_datetime, obs.obs_datetime, obs2.obs_datetime ) temp) temp2 GROUP BY ageGroup";
   
		return jdbcTemplate.queryForList(query);
	}
}  