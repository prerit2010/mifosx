/**
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package org.mifosplatform.portfolio.savings.service;

import static org.mifosplatform.portfolio.savings.SavingsApiConstants.SAVINGS_ACCOUNT_RESOURCE_NAME;
import static org.mifosplatform.portfolio.savings.SavingsApiConstants.amountParamName;
import static org.mifosplatform.portfolio.savings.SavingsApiConstants.chargeIdParamName;
import static org.mifosplatform.portfolio.savings.SavingsApiConstants.dueAsOfDateParamName;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.lang.StringUtils;
import org.joda.time.LocalDate;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.mifosplatform.accounting.journalentry.service.JournalEntryWritePlatformService;
import org.mifosplatform.infrastructure.core.api.JsonCommand;
import org.mifosplatform.infrastructure.core.data.ApiParameterError;
import org.mifosplatform.infrastructure.core.data.CommandProcessingResult;
import org.mifosplatform.infrastructure.core.data.CommandProcessingResultBuilder;
import org.mifosplatform.infrastructure.core.data.DataValidatorBuilder;
import org.mifosplatform.infrastructure.core.exception.PlatformApiDataValidationException;
import org.mifosplatform.infrastructure.core.exception.PlatformServiceUnavailableException;
import org.mifosplatform.infrastructure.core.service.DateUtils;
import org.mifosplatform.infrastructure.security.service.PlatformSecurityContext;
import org.mifosplatform.organisation.holiday.service.HolidayWritePlatformService;
import org.mifosplatform.organisation.monetary.domain.ApplicationCurrency;
import org.mifosplatform.organisation.monetary.domain.ApplicationCurrencyRepositoryWrapper;
import org.mifosplatform.organisation.monetary.domain.MonetaryCurrency;
import org.mifosplatform.organisation.monetary.domain.Money;
import org.mifosplatform.organisation.office.domain.Office;
import org.mifosplatform.organisation.staff.domain.Staff;
import org.mifosplatform.organisation.workingdays.service.WorkingDaysWritePlatformService;
import org.mifosplatform.portfolio.account.PortfolioAccountType;
import org.mifosplatform.portfolio.account.data.AccountTransferDTO;
import org.mifosplatform.portfolio.account.data.PortfolioAccountData;
import org.mifosplatform.portfolio.account.domain.AccountTransferType;
import org.mifosplatform.portfolio.account.service.AccountAssociationsReadPlatformService;
import org.mifosplatform.portfolio.account.service.AccountTransfersReadPlatformService;
import org.mifosplatform.portfolio.account.service.AccountTransfersWritePlatformService;
import org.mifosplatform.portfolio.charge.domain.Charge;
import org.mifosplatform.portfolio.charge.domain.ChargeRepositoryWrapper;
import org.mifosplatform.portfolio.client.domain.Client;
import org.mifosplatform.portfolio.client.exception.ClientNotActiveException;
import org.mifosplatform.portfolio.group.domain.Group;
import org.mifosplatform.portfolio.group.exception.GroupNotActiveException;
import org.mifosplatform.portfolio.note.domain.Note;
import org.mifosplatform.portfolio.note.domain.NoteRepository;
import org.mifosplatform.portfolio.paymentdetail.domain.PaymentDetail;
import org.mifosplatform.portfolio.paymentdetail.service.PaymentDetailWritePlatformService;
import org.mifosplatform.portfolio.savings.DepositAccountType;
import org.mifosplatform.portfolio.savings.SavingsAccountTransactionType;
import org.mifosplatform.portfolio.savings.SavingsApiConstants;
import org.mifosplatform.portfolio.savings.data.DepositAccountTransactionDataValidator;
import org.mifosplatform.portfolio.savings.data.SavingsAccountChargeDataValidator;
import org.mifosplatform.portfolio.savings.data.SavingsAccountTransactionDTO;
import org.mifosplatform.portfolio.savings.domain.DepositAccountAssembler;
import org.mifosplatform.portfolio.savings.domain.DepositAccountDomainService;
import org.mifosplatform.portfolio.savings.domain.FixedDepositAccount;
import org.mifosplatform.portfolio.savings.domain.RecurringDepositAccount;
import org.mifosplatform.portfolio.savings.domain.SavingsAccount;
import org.mifosplatform.portfolio.savings.domain.SavingsAccountCharge;
import org.mifosplatform.portfolio.savings.domain.SavingsAccountChargeRepositoryWrapper;
import org.mifosplatform.portfolio.savings.domain.SavingsAccountRepository;
import org.mifosplatform.portfolio.savings.domain.SavingsAccountStatusType;
import org.mifosplatform.portfolio.savings.domain.SavingsAccountTransaction;
import org.mifosplatform.portfolio.savings.domain.SavingsAccountTransactionRepository;
import org.mifosplatform.portfolio.savings.exception.DepositAccountTransactionNotAllowedException;
import org.mifosplatform.portfolio.savings.exception.SavingsAccountTransactionNotFoundException;
import org.mifosplatform.portfolio.savings.exception.TransactionUpdateNotAllowedException;
import org.mifosplatform.useradministration.domain.AppUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepositAccountWritePlatformServiceJpaRepositoryImpl implements DepositAccountWritePlatformService {

    private final PlatformSecurityContext context;
    private final SavingsAccountRepository savingAccountRepository;
    private final SavingsAccountTransactionRepository savingsAccountTransactionRepository;
    private final DepositAccountAssembler depositAccountAssembler;
    private final DepositAccountTransactionDataValidator depositAccountTransactionDataValidator;
    private final SavingsAccountChargeDataValidator savingsAccountChargeDataValidator;
    private final PaymentDetailWritePlatformService paymentDetailWritePlatformService;
    private final ApplicationCurrencyRepositoryWrapper applicationCurrencyRepositoryWrapper;
    private final JournalEntryWritePlatformService journalEntryWritePlatformService;
    private final DepositAccountDomainService depositAccountDomainService;
    private final NoteRepository noteRepository;
    private final AccountTransfersReadPlatformService accountTransfersReadPlatformService;
    private final ChargeRepositoryWrapper chargeRepository;
    private final SavingsAccountChargeRepositoryWrapper savingsAccountChargeRepository;
    private final HolidayWritePlatformService holidayWritePlatformService;
    private final WorkingDaysWritePlatformService workingDaysWritePlatformService;
    private final AccountAssociationsReadPlatformService accountAssociationsReadPlatformService;
    private final AccountTransfersWritePlatformService accountTransfersWritePlatformService;

    @Autowired
    public DepositAccountWritePlatformServiceJpaRepositoryImpl(final PlatformSecurityContext context,
            final SavingsAccountRepository savingAccountRepository,
            final SavingsAccountTransactionRepository savingsAccountTransactionRepository,
            final DepositAccountAssembler depositAccountAssembler,
            final DepositAccountTransactionDataValidator depositAccountTransactionDataValidator,
            final SavingsAccountChargeDataValidator savingsAccountChargeDataValidator,
            final PaymentDetailWritePlatformService paymentDetailWritePlatformService,
            final ApplicationCurrencyRepositoryWrapper applicationCurrencyRepositoryWrapper,
            final JournalEntryWritePlatformService journalEntryWritePlatformService,
            final DepositAccountDomainService depositAccountDomainService, final NoteRepository noteRepository,
            final AccountTransfersReadPlatformService accountTransfersReadPlatformService, final ChargeRepositoryWrapper chargeRepository,
            final SavingsAccountChargeRepositoryWrapper savingsAccountChargeRepository,
            final HolidayWritePlatformService holidayWritePlatformService,
            final WorkingDaysWritePlatformService workingDaysWritePlatformService,
            final AccountAssociationsReadPlatformService accountAssociationsReadPlatformService,
            final AccountTransfersWritePlatformService accountTransfersWritePlatformService) {
        this.context = context;
        this.savingAccountRepository = savingAccountRepository;
        this.savingsAccountTransactionRepository = savingsAccountTransactionRepository;
        this.depositAccountAssembler = depositAccountAssembler;
        this.depositAccountTransactionDataValidator = depositAccountTransactionDataValidator;
        this.savingsAccountChargeDataValidator = savingsAccountChargeDataValidator;
        this.paymentDetailWritePlatformService = paymentDetailWritePlatformService;
        this.applicationCurrencyRepositoryWrapper = applicationCurrencyRepositoryWrapper;
        this.journalEntryWritePlatformService = journalEntryWritePlatformService;
        this.depositAccountDomainService = depositAccountDomainService;
        this.noteRepository = noteRepository;
        this.accountTransfersReadPlatformService = accountTransfersReadPlatformService;
        this.chargeRepository = chargeRepository;
        this.savingsAccountChargeRepository = savingsAccountChargeRepository;
        this.holidayWritePlatformService = holidayWritePlatformService;
        this.workingDaysWritePlatformService = workingDaysWritePlatformService;
        this.accountAssociationsReadPlatformService = accountAssociationsReadPlatformService;
        this.accountTransfersWritePlatformService = accountTransfersWritePlatformService;
    }

    @Transactional
    @Override
    public CommandProcessingResult activate(final Long savingsId, final JsonCommand command, final DepositAccountType depositAccountType) {

        final AppUser user = this.context.authenticatedUser();

        this.depositAccountTransactionDataValidator.validateActivation(command);

        final SavingsAccount account = this.depositAccountAssembler.assembleFrom(savingsId, depositAccountType);
        checkClientOrGroupActive(account);

        final Set<Long> existingTransactionIds = new HashSet<Long>();
        final Set<Long> existingReversedTransactionIds = new HashSet<Long>();
        updateExistingTransactionsDetails(account, existingTransactionIds, existingReversedTransactionIds);

        final Map<String, Object> changes = account.activate(user, command, DateUtils.getLocalDateOfTenant());
        if (!changes.isEmpty()) {
            final Locale locale = command.extractLocale();
            final DateTimeFormatter fmt = DateTimeFormat.forPattern(command.dateFormat()).withLocale(locale);
            Money amountForDeposit = account.activateWithBalance();
            if (amountForDeposit.isGreaterThanZero()) {
                final PortfolioAccountData portfolioAccountData = this.accountAssociationsReadPlatformService
                        .retriveSavingsAssociation(savingsId);
                if (portfolioAccountData == null) {
                    this.depositAccountDomainService.handleDeposit(account, fmt, account.getActivationLocalDate(),
                            amountForDeposit.getAmount(), null);
                } else {
                    final AccountTransferDTO accountTransferDTO = new AccountTransferDTO(account.getActivationLocalDate(),
                            amountForDeposit.getAmount(), PortfolioAccountType.SAVINGS, PortfolioAccountType.SAVINGS,
                            portfolioAccountData.accountId(), account.getId(), "Account Transfer", locale, fmt, null, null, null, null,
                            null, AccountTransferType.ACCOUNT_TRANSFER.getValue(), null, null, null, null, account);
                    this.accountTransfersWritePlatformService.transferFunds(accountTransferDTO);
                }
                updateExistingTransactionsDetails(account, existingTransactionIds, existingReversedTransactionIds);
            }
            // account.processAccountUponActivation(fmt);

            account.validateAccountBalanceDoesNotBecomeNegative(SavingsAccountTransactionType.PAY_CHARGE.name());

            this.savingAccountRepository.save(account);
        }

        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds);

        return new CommandProcessingResultBuilder() //
                .withEntityId(savingsId) //
                .withOfficeId(account.officeId()) //
                .withClientId(account.clientId()) //
                .withGroupId(account.groupId()) //
                .withSavingsId(savingsId) //
                .with(changes) //
                .build();
    }

    @Transactional
    @Override
    public CommandProcessingResult deposit(final Long savingsId, final JsonCommand command, final DepositAccountType depositAccountType) {

        this.context.authenticatedUser();

        if (depositAccountType.isFixedDeposit()) { throw new DepositAccountTransactionNotAllowedException(savingsId, "deposit",
                depositAccountType); }

        this.depositAccountTransactionDataValidator.validate(command, depositAccountType);

        final SavingsAccount account = this.depositAccountAssembler.assembleFrom(savingsId, depositAccountType);
        checkClientOrGroupActive(account);

        final Locale locale = command.extractLocale();
        final DateTimeFormatter fmt = DateTimeFormat.forPattern(command.dateFormat()).withLocale(locale);

        final LocalDate transactionDate = command.localDateValueOfParameterNamed("transactionDate");
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed("transactionAmount");

        final Map<String, Object> changes = new LinkedHashMap<String, Object>();
        final PaymentDetail paymentDetail = this.paymentDetailWritePlatformService.createAndPersistPaymentDetail(command, changes);

        final SavingsAccountTransaction deposit = this.depositAccountDomainService.handleDeposit(account, fmt, transactionDate,
                transactionAmount, paymentDetail);

        return new CommandProcessingResultBuilder() //
                .withEntityId(deposit.getId()) //
                .withOfficeId(account.officeId()) //
                .withClientId(account.clientId()) //
                .withGroupId(account.groupId()) //
                .withSavingsId(savingsId) //
                .with(changes) //
                .build();
    }

    private Long saveTransactionToGenerateTransactionId(final SavingsAccountTransaction transaction) {
        this.savingsAccountTransactionRepository.save(transaction);
        return transaction.getId();
    }

    @Transactional
    @Override
    public CommandProcessingResult withdrawal(final Long savingsId, final JsonCommand command, final DepositAccountType depositAccountType) {

        this.depositAccountTransactionDataValidator.validate(command, depositAccountType);

        if (depositAccountType.isFixedDeposit()) { throw new DepositAccountTransactionNotAllowedException(savingsId, "withdraw",
                depositAccountType); }

        final LocalDate transactionDate = command.localDateValueOfParameterNamed("transactionDate");
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed("transactionAmount");

        final Locale locale = command.extractLocale();
        final DateTimeFormatter fmt = DateTimeFormat.forPattern(command.dateFormat()).withLocale(locale);

        final Map<String, Object> changes = new LinkedHashMap<String, Object>();
        final PaymentDetail paymentDetail = this.paymentDetailWritePlatformService.createAndPersistPaymentDetail(command, changes);

        final SavingsAccount account = this.depositAccountAssembler.assembleFrom(savingsId, depositAccountType);
        checkClientOrGroupActive(account);

        final SavingsAccountTransaction withdrawal = this.depositAccountDomainService.handleWithdrawal(account, fmt, transactionDate,
                transactionAmount, paymentDetail, true);

        return new CommandProcessingResultBuilder() //
                .withEntityId(withdrawal.getId()) //
                .withOfficeId(account.officeId()) //
                .withClientId(account.clientId()) //
                .withGroupId(account.groupId()) //
                .withSavingsId(savingsId) //
                .with(changes)//
                .build();
    }

    @Transactional
    @Override
    public CommandProcessingResult calculateInterest(final Long savingsId, final DepositAccountType depositAccountType) {

        final SavingsAccount account = this.depositAccountAssembler.assembleFrom(savingsId, depositAccountType);
        checkClientOrGroupActive(account);

        final LocalDate today = DateUtils.getLocalDateOfTenant();
        final MathContext mc = new MathContext(15, RoundingMode.HALF_EVEN);

        account.calculateInterestUsing(mc, today);

        this.savingAccountRepository.save(account);

        return new CommandProcessingResultBuilder() //
                .withEntityId(savingsId) //
                .withOfficeId(account.officeId()) //
                .withClientId(account.clientId()) //
                .withGroupId(account.groupId()) //
                .withSavingsId(savingsId) //
                .build();
    }

    @Transactional
    @Override
    public CommandProcessingResult postInterest(final Long savingsId, final DepositAccountType depositAccountType) {

        final SavingsAccount account = this.depositAccountAssembler.assembleFrom(savingsId, depositAccountType);
        checkClientOrGroupActive(account);
        postInterest(account);
        return new CommandProcessingResultBuilder() //
                .withEntityId(savingsId) //
                .withOfficeId(account.officeId()) //
                .withClientId(account.clientId()) //
                .withGroupId(account.groupId()) //
                .withSavingsId(savingsId) //
                .build();
    }

    @Transactional
    private void postInterest(final SavingsAccount account) {
        final Set<Long> existingTransactionIds = new HashSet<Long>();
        final Set<Long> existingReversedTransactionIds = new HashSet<Long>();
        updateExistingTransactionsDetails(account, existingTransactionIds, existingReversedTransactionIds);
        final LocalDate today = DateUtils.getLocalDateOfTenant();
        final MathContext mc = new MathContext(10, RoundingMode.HALF_EVEN);

        account.postInterest(mc, today);
        this.savingAccountRepository.save(account);

        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds);
    }

    @Override
    public CommandProcessingResult undoTransaction(final Long savingsId, final Long transactionId,
            final boolean allowAccountTransferModification, final DepositAccountType depositAccountType) {

        if (depositAccountType.isFixedDeposit()) { throw new DepositAccountTransactionNotAllowedException(savingsId, "undo",
                depositAccountType); }

        final SavingsAccount account = this.depositAccountAssembler.assembleFrom(savingsId, depositAccountType);
        final Set<Long> existingTransactionIds = new HashSet<Long>();
        final Set<Long> existingReversedTransactionIds = new HashSet<Long>();
        updateExistingTransactionsDetails(account, existingTransactionIds, existingReversedTransactionIds);

        final SavingsAccountTransaction savingsAccountTransaction = this.savingsAccountTransactionRepository
                .findOneByIdAndSavingsAccountId(transactionId, savingsId);
        if (savingsAccountTransaction == null) { throw new SavingsAccountTransactionNotFoundException(savingsId, transactionId); }

        if (!allowAccountTransferModification
                && this.accountTransfersReadPlatformService.isAccountTransfer(transactionId, PortfolioAccountType.SAVINGS)) { throw new PlatformServiceUnavailableException(
                "error.msg.saving.account.transfer.transaction.update.not.allowed", "Savings account transaction:" + transactionId
                        + " update not allowed as it involves in account transfer", transactionId); }

        final LocalDate today = DateUtils.getLocalDateOfTenant();
        final MathContext mc = new MathContext(15, RoundingMode.HALF_EVEN);

        if (account.isNotActive()) {
            throwValidationForActiveStatus(SavingsApiConstants.undoTransactionAction);
        }
        account.undoTransaction(transactionId);

        checkClientOrGroupActive(account);
        if (savingsAccountTransaction.isPostInterestCalculationRequired()
                && account.isBeforeLastPostingPeriod(savingsAccountTransaction.transactionLocalDate())) {
            account.postInterest(mc, today);
        } else {
            account.calculateInterestUsing(mc, today);
        }
        account.validateAccountBalanceDoesNotBecomeNegative(SavingsApiConstants.undoTransactionAction);
        account.activateAccountBasedOnBalance();
        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds);

        return new CommandProcessingResultBuilder() //
                .withEntityId(savingsId) //
                .withOfficeId(account.officeId()) //
                .withClientId(account.clientId()) //
                .withGroupId(account.groupId()) //
                .withSavingsId(savingsId) //
                .build();
    }

    @Override
    public CommandProcessingResult adjustDepositTransaction(final Long savingsId, final Long transactionId, final JsonCommand command,
            final DepositAccountType depositAccountType) {

        if (depositAccountType.isFixedDeposit()) { throw new DepositAccountTransactionNotAllowedException(savingsId, "modify",
                depositAccountType); }
        this.depositAccountTransactionDataValidator.validate(command, depositAccountType);

        final SavingsAccountTransaction savingsAccountTransaction = this.savingsAccountTransactionRepository
                .findOneByIdAndSavingsAccountId(transactionId, savingsId);
        if (savingsAccountTransaction == null) { throw new SavingsAccountTransactionNotFoundException(savingsId, transactionId); }

        if (!(savingsAccountTransaction.isDeposit() || savingsAccountTransaction.isWithdrawal()) || savingsAccountTransaction.isReversed()) { throw new TransactionUpdateNotAllowedException(
                savingsId, transactionId); }

        if (this.accountTransfersReadPlatformService.isAccountTransfer(transactionId, PortfolioAccountType.SAVINGS)) { throw new PlatformServiceUnavailableException(
                "error.msg.saving.account.transfer.transaction.update.not.allowed", "Deposit account transaction:" + transactionId
                        + " update not allowed as it involves in account transfer", transactionId); }

        final LocalDate today = DateUtils.getLocalDateOfTenant();

        final SavingsAccount account = this.depositAccountAssembler.assembleFrom(savingsId, depositAccountType);
        if (account.isNotActive()) {
            throwValidationForActiveStatus(SavingsApiConstants.adjustTransactionAction);
        }
        final Set<Long> existingTransactionIds = new HashSet<Long>();
        final Set<Long> existingReversedTransactionIds = new HashSet<Long>();
        updateExistingTransactionsDetails(account, existingTransactionIds, existingReversedTransactionIds);

        final Locale locale = command.extractLocale();
        final DateTimeFormatter fmt = DateTimeFormat.forPattern(command.dateFormat()).withLocale(locale);
        final LocalDate transactionDate = command.localDateValueOfParameterNamed(SavingsApiConstants.transactionDateParamName);
        final BigDecimal transactionAmount = command.bigDecimalValueOfParameterNamed(SavingsApiConstants.transactionAmountParamName);
        final Map<String, Object> changes = new LinkedHashMap<String, Object>();
        final PaymentDetail paymentDetail = this.paymentDetailWritePlatformService.createAndPersistPaymentDetail(command, changes);

        final MathContext mc = new MathContext(10, RoundingMode.HALF_EVEN);
        account.undoTransaction(transactionId);

        SavingsAccountTransaction transaction = null;
        if (savingsAccountTransaction.isDeposit()) {
            final SavingsAccountTransactionDTO transactionDTO = new SavingsAccountTransactionDTO(fmt, transactionDate, transactionAmount,
                    paymentDetail, savingsAccountTransaction.createdDate());
            transaction = account.deposit(transactionDTO);
        } else {
            final SavingsAccountTransactionDTO transactionDTO = new SavingsAccountTransactionDTO(fmt, transactionDate, transactionAmount,
                    paymentDetail, savingsAccountTransaction.createdDate());
            transaction = account.withdraw(transactionDTO, true);
        }
        final Long newtransactionId = saveTransactionToGenerateTransactionId(transaction);

        if (account.isBeforeLastPostingPeriod(transactionDate)
                || account.isBeforeLastPostingPeriod(savingsAccountTransaction.transactionLocalDate())) {
            account.postInterest(mc, today);
        } else {
            account.calculateInterestUsing(mc, today);
        }
        account.validateAccountBalanceDoesNotBecomeNegative(SavingsApiConstants.adjustTransactionAction);
        account.activateAccountBasedOnBalance();
        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds);
        return new CommandProcessingResultBuilder() //
                .withEntityId(newtransactionId) //
                .withOfficeId(account.officeId()) //
                .withClientId(account.clientId()) //
                .withGroupId(account.groupId()) //
                .withSavingsId(savingsId) //
                .with(changes)//
                .build();
    }

    /**
     *
     */
    private void throwValidationForActiveStatus(final String actionName) {
        final List<ApiParameterError> dataValidationErrors = new ArrayList<ApiParameterError>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(SAVINGS_ACCOUNT_RESOURCE_NAME + actionName);
        baseDataValidator.reset().failWithCodeNoParameterAddedToErrorCode("account.is.not.active");
        throw new PlatformApiDataValidationException(dataValidationErrors);
    }

    private void checkClientOrGroupActive(final SavingsAccount account) {
        final Client client = account.getClient();
        if (client != null) {
            if (client.isNotActive()) { throw new ClientNotActiveException(client.getId()); }
        }
        final Group group = account.group();
        if (group != null) {
            if (group.isNotActive()) { throw new GroupNotActiveException(group.getId()); }
        }
    }

    @Override
    public CommandProcessingResult close(final Long savingsId, final JsonCommand command, final DepositAccountType depositAccountType) {
        final AppUser user = this.context.authenticatedUser();

        this.depositAccountTransactionDataValidator.validateClosing(command, depositAccountType, false);

        final Map<String, Object> changes = new LinkedHashMap<String, Object>();
        final PaymentDetail paymentDetail = this.paymentDetailWritePlatformService.createAndPersistPaymentDetail(command, changes);

        final SavingsAccount account = this.depositAccountAssembler.assembleFrom(savingsId, depositAccountType);
        checkClientOrGroupActive(account);

        final SavingsAccountTransaction withdrawal = this.depositAccountDomainService.handleAccountClosure(account, paymentDetail, user,
                command, DateUtils.getLocalDateOfTenant(), changes);

        final String noteText = command.stringValueOfParameterNamed("note");
        if (StringUtils.isNotBlank(noteText)) {
            final Note note = Note.savingNote(account, noteText);
            changes.put("note", noteText);
            this.noteRepository.save(note);
        }

        return new CommandProcessingResultBuilder() //
                .withEntityId(withdrawal.getId()) //
                .withOfficeId(account.officeId()) //
                .withClientId(account.clientId()) //
                .withGroupId(account.groupId()) //
                .withSavingsId(savingsId) //
                .with(changes)//
                .build();

    }

    @Override
    public CommandProcessingResult prematureClose(final Long savingsId, final JsonCommand command,
            final DepositAccountType depositAccountType) {
        final AppUser user = this.context.authenticatedUser();

        this.depositAccountTransactionDataValidator.validateClosing(command, depositAccountType, true);

        final Map<String, Object> changes = new LinkedHashMap<String, Object>();
        final PaymentDetail paymentDetail = this.paymentDetailWritePlatformService.createAndPersistPaymentDetail(command, changes);

        final SavingsAccount account = this.depositAccountAssembler.assembleFrom(savingsId, depositAccountType);
        checkClientOrGroupActive(account);

        final SavingsAccountTransaction withdrawal = this.depositAccountDomainService.handleAccountPreMatureClosure(account, paymentDetail,
                user, command, DateUtils.getLocalDateOfTenant(), changes);

        final String noteText = command.stringValueOfParameterNamed("note");
        if (StringUtils.isNotBlank(noteText)) {
            final Note note = Note.savingNote(account, noteText);
            changes.put("note", noteText);
            this.noteRepository.save(note);
        }

        return new CommandProcessingResultBuilder() //
                .withEntityId(withdrawal.getId()) //
                .withOfficeId(account.officeId()) //
                .withClientId(account.clientId()) //
                .withGroupId(account.groupId()) //
                .withSavingsId(savingsId) //
                .with(changes)//
                .build();

    }

    @Override
    public SavingsAccountTransaction initiateSavingsTransfer(final Long accountId, final LocalDate transferDate,
            final DepositAccountType depositAccountType) {
        final SavingsAccount savingsAccount = this.depositAccountAssembler.assembleFrom(accountId, depositAccountType);

        final Set<Long> existingTransactionIds = new HashSet<Long>();
        final Set<Long> existingReversedTransactionIds = new HashSet<Long>();
        updateExistingTransactionsDetails(savingsAccount, existingTransactionIds, existingReversedTransactionIds);

        final SavingsAccountTransaction newTransferTransaction = SavingsAccountTransaction.initiateTransfer(savingsAccount,
                savingsAccount.office(), transferDate);
        savingsAccount.getTransactions().add(newTransferTransaction);
        savingsAccount.setStatus(SavingsAccountStatusType.TRANSFER_IN_PROGRESS.getValue());
        final MathContext mc = MathContext.DECIMAL64;
        savingsAccount.calculateInterestUsing(mc, transferDate);

        this.savingsAccountTransactionRepository.save(newTransferTransaction);
        this.savingAccountRepository.save(savingsAccount);

        postJournalEntries(savingsAccount, existingTransactionIds, existingReversedTransactionIds);

        return newTransferTransaction;
    }

    @Override
    public SavingsAccountTransaction withdrawSavingsTransfer(final Long accountId, final LocalDate transferDate,
            final DepositAccountType depositAccountType) {
        final SavingsAccount savingsAccount = this.depositAccountAssembler.assembleFrom(accountId, depositAccountType);

        final Set<Long> existingTransactionIds = new HashSet<Long>();
        final Set<Long> existingReversedTransactionIds = new HashSet<Long>();
        updateExistingTransactionsDetails(savingsAccount, existingTransactionIds, existingReversedTransactionIds);

        final SavingsAccountTransaction withdrawtransferTransaction = SavingsAccountTransaction.withdrawTransfer(savingsAccount,
                savingsAccount.office(), transferDate);
        savingsAccount.getTransactions().add(withdrawtransferTransaction);
        savingsAccount.setStatus(SavingsAccountStatusType.ACTIVE.getValue());
        final MathContext mc = MathContext.DECIMAL64;
        savingsAccount.calculateInterestUsing(mc, transferDate);

        this.savingsAccountTransactionRepository.save(withdrawtransferTransaction);
        this.savingAccountRepository.save(savingsAccount);

        postJournalEntries(savingsAccount, existingTransactionIds, existingReversedTransactionIds);

        return withdrawtransferTransaction;
    }

    @Override
    public void rejectSavingsTransfer(final Long accountId, final DepositAccountType depositAccountType) {
        final SavingsAccount savingsAccount = this.depositAccountAssembler.assembleFrom(accountId, depositAccountType);
        savingsAccount.setStatus(SavingsAccountStatusType.TRANSFER_ON_HOLD.getValue());
        this.savingAccountRepository.save(savingsAccount);
    }

    @Override
    public SavingsAccountTransaction acceptSavingsTransfer(final Long accountId, final LocalDate transferDate,
            final Office acceptedInOffice, final Staff fieldOfficer, final DepositAccountType depositAccountType) {
        final SavingsAccount savingsAccount = this.depositAccountAssembler.assembleFrom(accountId, depositAccountType);

        final Set<Long> existingTransactionIds = new HashSet<Long>();
        final Set<Long> existingReversedTransactionIds = new HashSet<Long>();
        updateExistingTransactionsDetails(savingsAccount, existingTransactionIds, existingReversedTransactionIds);

        final SavingsAccountTransaction acceptTransferTransaction = SavingsAccountTransaction.approveTransfer(savingsAccount,
                acceptedInOffice, transferDate);
        savingsAccount.getTransactions().add(acceptTransferTransaction);
        savingsAccount.setStatus(SavingsAccountStatusType.ACTIVE.getValue());
        if (fieldOfficer != null) {
            savingsAccount.update(fieldOfficer);
        }

        final MathContext mc = MathContext.DECIMAL64;
        savingsAccount.calculateInterestUsing(mc, transferDate);

        this.savingsAccountTransactionRepository.save(acceptTransferTransaction);
        this.savingAccountRepository.save(savingsAccount);

        postJournalEntries(savingsAccount, existingTransactionIds, existingReversedTransactionIds);

        return acceptTransferTransaction;
    }

    @Transactional
    @Override
    public CommandProcessingResult addSavingsAccountCharge(final JsonCommand command, final DepositAccountType depositAccountType) {

        this.context.authenticatedUser();
        final List<ApiParameterError> dataValidationErrors = new ArrayList<ApiParameterError>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(SAVINGS_ACCOUNT_RESOURCE_NAME);

        final Long savingsAccountId = command.getSavingsId();
        this.savingsAccountChargeDataValidator.validateAdd(command.json());

        final SavingsAccount savingsAccount = this.depositAccountAssembler.assembleFrom(savingsAccountId, depositAccountType);
        checkClientOrGroupActive(savingsAccount);

        final Locale locale = command.extractLocale();
        final String format = command.dateFormat();
        final DateTimeFormatter fmt = StringUtils.isNotBlank(format) ? DateTimeFormat.forPattern(format).withLocale(locale)
                : DateTimeFormat.forPattern("dd MM yyyy");

        final Long chargeDefinitionId = command.longValueOfParameterNamed(chargeIdParamName);
        final Charge chargeDefinition = this.chargeRepository.findOneWithNotFoundDetection(chargeDefinitionId);

        final SavingsAccountCharge savingsAccountCharge = SavingsAccountCharge.createNewFromJson(savingsAccount, chargeDefinition, command);

        if (savingsAccountCharge.getDueLocalDate() != null) {
            // transaction date should not be on a holiday or non working day
            if (!this.holidayWritePlatformService.isTransactionAllowedOnHoliday()
                    && this.holidayWritePlatformService.isHoliday(savingsAccount.officeId(), savingsAccountCharge.getDueLocalDate())) {
                baseDataValidator.reset().parameter(dueAsOfDateParamName).value(savingsAccountCharge.getDueLocalDate().toString(fmt))
                        .failWithCodeNoParameterAddedToErrorCode("charge.due.date.is.on.holiday");
                if (!dataValidationErrors.isEmpty()) { throw new PlatformApiDataValidationException(dataValidationErrors); }
            }

            if (!this.workingDaysWritePlatformService.isTransactionAllowedOnNonWorkingDay()
                    && !this.workingDaysWritePlatformService.isWorkingDay(savingsAccountCharge.getDueLocalDate())) {
                baseDataValidator.reset().parameter(dueAsOfDateParamName).value(savingsAccountCharge.getDueLocalDate().toString(fmt))
                        .failWithCodeNoParameterAddedToErrorCode("charge.due.date.is.a.nonworking.day");
                if (!dataValidationErrors.isEmpty()) { throw new PlatformApiDataValidationException(dataValidationErrors); }
            }
        }

        savingsAccount.addCharge(fmt, savingsAccountCharge, chargeDefinition);

        this.savingAccountRepository.saveAndFlush(savingsAccount);

        return new CommandProcessingResultBuilder() //
                .withEntityId(savingsAccountCharge.getId()) //
                .withOfficeId(savingsAccount.officeId()) //
                .withClientId(savingsAccount.clientId()) //
                .withGroupId(savingsAccount.groupId()) //
                .withSavingsId(savingsAccountId) //
                .build();
    }

    @Transactional
    @Override
    public CommandProcessingResult updateSavingsAccountCharge(final JsonCommand command, final DepositAccountType depositAccountType) {

        this.context.authenticatedUser();
        this.savingsAccountChargeDataValidator.validateUpdate(command.json());
        final List<ApiParameterError> dataValidationErrors = new ArrayList<ApiParameterError>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(SAVINGS_ACCOUNT_RESOURCE_NAME);

        final Long savingsAccountId = command.getSavingsId();
        // SavingsAccount Charge entity
        final Long savingsChargeId = command.entityId();

        final SavingsAccount savingsAccount = this.depositAccountAssembler.assembleFrom(savingsAccountId, depositAccountType);
        checkClientOrGroupActive(savingsAccount);

        final SavingsAccountCharge savingsAccountCharge = this.savingsAccountChargeRepository.findOneWithNotFoundDetection(savingsChargeId,
                savingsAccountId);

        final Map<String, Object> changes = savingsAccountCharge.update(command);

        if (savingsAccountCharge.getDueLocalDate() != null) {
            final Locale locale = command.extractLocale();
            final DateTimeFormatter fmt = DateTimeFormat.forPattern(command.dateFormat()).withLocale(locale);

            // transaction date should not be on a holiday or non working day
            if (!this.holidayWritePlatformService.isTransactionAllowedOnHoliday()
                    && this.holidayWritePlatformService.isHoliday(savingsAccount.officeId(), savingsAccountCharge.getDueLocalDate())) {
                baseDataValidator.reset().parameter(dueAsOfDateParamName).value(savingsAccountCharge.getDueLocalDate().toString(fmt))
                        .failWithCodeNoParameterAddedToErrorCode("charge.due.date.is.on.holiday");
                if (!dataValidationErrors.isEmpty()) { throw new PlatformApiDataValidationException(dataValidationErrors); }
            }

            if (!this.workingDaysWritePlatformService.isTransactionAllowedOnNonWorkingDay()
                    && !this.workingDaysWritePlatformService.isWorkingDay(savingsAccountCharge.getDueLocalDate())) {
                baseDataValidator.reset().parameter(dueAsOfDateParamName).value(savingsAccountCharge.getDueLocalDate().toString(fmt))
                        .failWithCodeNoParameterAddedToErrorCode("charge.due.date.is.a.nonworking.day");
                if (!dataValidationErrors.isEmpty()) { throw new PlatformApiDataValidationException(dataValidationErrors); }
            }
        }

        this.savingsAccountChargeRepository.saveAndFlush(savingsAccountCharge);

        return new CommandProcessingResultBuilder() //
                .withEntityId(savingsAccountCharge.getId()) //
                .withOfficeId(savingsAccountCharge.savingsAccount().officeId()) //
                .withClientId(savingsAccountCharge.savingsAccount().clientId()) //
                .withGroupId(savingsAccountCharge.savingsAccount().groupId()) //
                .withSavingsId(savingsAccountCharge.savingsAccount().getId()) //
                .with(changes) //
                .build();
    }

    @Transactional
    @Override
    public CommandProcessingResult waiveCharge(final Long savingsAccountId, final Long savingsAccountChargeId,
            @SuppressWarnings("unused") final DepositAccountType depositAccountType) {

        this.context.authenticatedUser();

        final SavingsAccountCharge savingsAccountCharge = this.savingsAccountChargeRepository.findOneWithNotFoundDetection(
                savingsAccountChargeId, savingsAccountId);

        // Get Savings account from savings charge
        final SavingsAccount account = savingsAccountCharge.savingsAccount();
        this.depositAccountAssembler.assignSavingAccountHelpers(account);

        final Set<Long> existingTransactionIds = new HashSet<Long>();
        final Set<Long> existingReversedTransactionIds = new HashSet<Long>();
        updateExistingTransactionsDetails(account, existingTransactionIds, existingReversedTransactionIds);

        account.waiveCharge(savingsAccountChargeId);

        final MathContext mc = MathContext.DECIMAL64;
        if (account.isBeforeLastPostingPeriod(savingsAccountCharge.getDueLocalDate())) {
            final LocalDate today = DateUtils.getLocalDateOfTenant();
            account.postInterest(mc, today);
        } else {
            final LocalDate today = DateUtils.getLocalDateOfTenant();
            account.calculateInterestUsing(mc, today);
        }

        account.validateAccountBalanceDoesNotBecomeNegative(SavingsApiConstants.waiveChargeTransactionAction);

        this.savingAccountRepository.saveAndFlush(account);

        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds);

        return new CommandProcessingResultBuilder() //
                .withEntityId(savingsAccountChargeId) //
                .withOfficeId(account.officeId()) //
                .withClientId(account.clientId()) //
                .withGroupId(account.groupId()) //
                .withSavingsId(savingsAccountId) //
                .build();
    }

    @Transactional
    @Override
    public CommandProcessingResult deleteSavingsAccountCharge(final Long savingsAccountId, final Long savingsAccountChargeId,
            @SuppressWarnings("unused") final JsonCommand command, final DepositAccountType depositAccountType) {
        this.context.authenticatedUser();

        final SavingsAccount savingsAccount = this.depositAccountAssembler.assembleFrom(savingsAccountId, depositAccountType);
        checkClientOrGroupActive(savingsAccount);
        final SavingsAccountCharge savingsAccountCharge = this.savingsAccountChargeRepository.findOneWithNotFoundDetection(
                savingsAccountChargeId, savingsAccountId);

        savingsAccount.removeCharge(savingsAccountCharge);
        this.savingAccountRepository.saveAndFlush(savingsAccount);

        return new CommandProcessingResultBuilder() //
                .withEntityId(savingsAccountChargeId) //
                .withOfficeId(savingsAccount.officeId()) //
                .withClientId(savingsAccount.clientId()) //
                .withGroupId(savingsAccount.groupId()) //
                .withSavingsId(savingsAccountId) //
                .build();
    }

    @Override
    public CommandProcessingResult payCharge(final Long savingsAccountId, final Long savingsAccountChargeId, final JsonCommand command,
            @SuppressWarnings("unused") final DepositAccountType depositAccountType) {

        this.context.authenticatedUser();
        this.savingsAccountChargeDataValidator.validatePayCharge(command.json());
        final Locale locale = command.extractLocale();
        final DateTimeFormatter fmt = DateTimeFormat.forPattern(command.dateFormat()).withLocale(locale);
        final BigDecimal amountPaid = command.bigDecimalValueOfParameterNamed(amountParamName);
        final LocalDate transactionDate = command.localDateValueOfParameterNamed(dueAsOfDateParamName);

        final SavingsAccountCharge savingsAccountCharge = this.savingsAccountChargeRepository.findOneWithNotFoundDetection(
                savingsAccountChargeId, savingsAccountId);

        final List<ApiParameterError> dataValidationErrors = new ArrayList<ApiParameterError>();
        final DataValidatorBuilder baseDataValidator = new DataValidatorBuilder(dataValidationErrors)
                .resource(SAVINGS_ACCOUNT_RESOURCE_NAME);

        // transaction date should not be on a holiday or non working day
        if (!this.holidayWritePlatformService.isTransactionAllowedOnHoliday()
                && this.holidayWritePlatformService.isHoliday(savingsAccountCharge.savingsAccount().officeId(), transactionDate)) {
            baseDataValidator.reset().parameter(dueAsOfDateParamName).value(transactionDate.toString(fmt))
                    .failWithCodeNoParameterAddedToErrorCode("transaction.not.allowed.transaction.date.is.on.holiday");
            if (!dataValidationErrors.isEmpty()) { throw new PlatformApiDataValidationException(dataValidationErrors); }
        }

        if (!this.workingDaysWritePlatformService.isTransactionAllowedOnNonWorkingDay()
                && !this.workingDaysWritePlatformService.isWorkingDay(transactionDate)) {
            baseDataValidator.reset().parameter(dueAsOfDateParamName).value(transactionDate.toString(fmt))
                    .failWithCodeNoParameterAddedToErrorCode("transaction.not.allowed.transaction.date.is.a.nonworking.day");
            if (!dataValidationErrors.isEmpty()) { throw new PlatformApiDataValidationException(dataValidationErrors); }
        }

        this.payCharge(savingsAccountCharge, transactionDate, amountPaid, fmt);
        return new CommandProcessingResultBuilder() //
                .withEntityId(savingsAccountCharge.getId()) //
                .withOfficeId(savingsAccountCharge.savingsAccount().officeId()) //
                .withClientId(savingsAccountCharge.savingsAccount().clientId()) //
                .withGroupId(savingsAccountCharge.savingsAccount().groupId()) //
                .withSavingsId(savingsAccountCharge.savingsAccount().getId()) //
                .build();

    }

    @Transactional
    @Override
    public void applyChargeDue(final Long savingsAccountChargeId, final Long accountId,
            @SuppressWarnings("unused") final DepositAccountType depositAccountType) {
        // always use current date as transaction date for batch job
        final LocalDate transactionDate = DateUtils.getLocalDateOfTenant();
        final SavingsAccountCharge savingsAccountCharge = this.savingsAccountChargeRepository.findOneWithNotFoundDetection(
                savingsAccountChargeId, accountId);

        final DateTimeFormatter fmt = DateTimeFormat.forPattern("dd MM yyyy");

        while (transactionDate.isAfter(savingsAccountCharge.getDueLocalDate())) {
            payCharge(savingsAccountCharge, transactionDate, savingsAccountCharge.amoutOutstanding(), fmt);
        }
    }

    @Transactional
    private void payCharge(final SavingsAccountCharge savingsAccountCharge, final LocalDate transactionDate, final BigDecimal amountPaid,
            final DateTimeFormatter formatter) {
        // Get Savings account from savings charge
        final SavingsAccount account = savingsAccountCharge.savingsAccount();
        this.depositAccountAssembler.assignSavingAccountHelpers(account);
        final Set<Long> existingTransactionIds = new HashSet<Long>();
        final Set<Long> existingReversedTransactionIds = new HashSet<Long>();
        updateExistingTransactionsDetails(account, existingTransactionIds, existingReversedTransactionIds);
        account.payCharge(savingsAccountCharge, amountPaid, transactionDate, formatter);

        final MathContext mc = MathContext.DECIMAL64;
        if (account.isBeforeLastPostingPeriod(transactionDate)) {
            final LocalDate today = DateUtils.getLocalDateOfTenant();
            account.postInterest(mc, today);
        } else {
            final LocalDate today = DateUtils.getLocalDateOfTenant();
            account.calculateInterestUsing(mc, today);
        }

        account.validateAccountBalanceDoesNotBecomeNegative("." + SavingsAccountTransactionType.PAY_CHARGE.getCode());

        this.savingAccountRepository.save(account);

        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds);
    }

    @Transactional
    @Override
    public void updateMaturityDetails(Long depositAccountId, DepositAccountType depositAccountType) {
        final SavingsAccount account = this.depositAccountAssembler.assembleFrom(depositAccountId, depositAccountType);
        final Set<Long> existingTransactionIds = new HashSet<Long>();
        final Set<Long> existingReversedTransactionIds = new HashSet<Long>();
        updateExistingTransactionsDetails(account, existingTransactionIds, existingReversedTransactionIds);

        if (depositAccountType.isFixedDeposit()) {
            ((FixedDepositAccount) account).updateMaturityStatus();
        } else if (depositAccountType.isRecurringDeposit()) {
            ((RecurringDepositAccount) account).updateMaturityStatus();
        }

        postJournalEntries(account, existingTransactionIds, existingReversedTransactionIds);
    }

    private void updateExistingTransactionsDetails(SavingsAccount account, Set<Long> existingTransactionIds,
            Set<Long> existingReversedTransactionIds) {
        existingTransactionIds.addAll(account.findExistingTransactionIds());
        existingReversedTransactionIds.addAll(account.findExistingReversedTransactionIds());
    }

    private void postJournalEntries(final SavingsAccount savingsAccount, final Set<Long> existingTransactionIds,
            final Set<Long> existingReversedTransactionIds) {

        final MonetaryCurrency currency = savingsAccount.getCurrency();
        final ApplicationCurrency applicationCurrency = this.applicationCurrencyRepositoryWrapper.findOneWithNotFoundDetection(currency);

        final Map<String, Object> accountingBridgeData = savingsAccount.deriveAccountingBridgeData(applicationCurrency.toData(),
                existingTransactionIds, existingReversedTransactionIds);
        this.journalEntryWritePlatformService.createJournalEntriesForSavings(accountingBridgeData);
    }
}