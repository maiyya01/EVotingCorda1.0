package com.example.flow

import co.paralleluniverse.fibers.Suspendable
import com.example.contract.VotingContract
import com.example.state.VotingrState
import net.corda.core.contracts.*
import net.corda.core.flows.*
import net.corda.core.identity.Party
import net.corda.core.transactions.SignedTransaction
import net.corda.core.transactions.TransactionBuilder
import net.corda.core.utilities.ProgressTracker
import net.corda.core.flows.FinalityFlow as FinalityFlow1



// *********
// * Flows *
// *********


@InitiatingFlow
@StartableByRPC
class CastVoteInitiator(
        val owner: Party,
        val candidateName: String,
        val votes: Int


) : FlowLogic<SignedTransaction>() {


    /**
     * The progress tracker checkpoints each stage of the flow and outputs the specified messages when each
     * checkpoint is reached in the code. See the 'progressTracker.currentStep' expressions within the call() function.
     */
    companion object {
        object GENERATING_TRANSACTION : ProgressTracker.Step("Generating transaction based on a new Commercial Paper.")
        object VERIFYING_TRANSACTION : ProgressTracker.Step("Verifying contract constraints.")
        object SIGNING_TRANSACTION : ProgressTracker.Step("Signing transaction with our private key.")
        object GATHERING_SIGS : ProgressTracker.Step("Gathering the counterparty's signature.") {
            override fun childProgressTracker() = CollectSignaturesFlow.tracker()
        }

        object FINALISING_TRANSACTION : ProgressTracker.Step("Obtaining notary signature and recording transaction.") {
            override fun childProgressTracker() = FinalityFlow1.tracker()
        }

        fun tracker() = ProgressTracker(
                GENERATING_TRANSACTION,
                VERIFYING_TRANSACTION,
                SIGNING_TRANSACTION,
                GATHERING_SIGS,
                FINALISING_TRANSACTION
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    override fun call() : SignedTransaction {
        // Initiator flow logic goes here.
        val notary = serviceHub.networkMapCache.notaryIdentities.first()

        val command = Command(VotingContract.Commands.CastVote(), listOf(owner).map { it.owningKey })

        val votingState = VotingrState(
                owner,
                candidateName,
                votes,
                UniqueIdentifier()
        )

        val txBuilder = TransactionBuilder(notary)
                .addOutputState(votingState, VotingContract.ID)
                .addCommand(command)

        txBuilder.verify(serviceHub)
        val tx = serviceHub.signInitialTransaction(txBuilder)

        val sessions = (votingState.participants  - ourIdentity).map { initiateFlow(it as Party) }
        val stx = subFlow(CollectSignaturesFlow(tx, sessions))
        return subFlow(FinalityFlow1(stx, sessions))
    }
}

@InitiatedBy(CastVoteInitiator::class)
class CasteVoteResponder(val counterpartySession: FlowSession) : FlowLogic<SignedTransaction>() {

    @Suspendable
    override fun call() : SignedTransaction  {
        val signedTransactionFlow = object : SignTransactionFlow(counterpartySession)
        {
            override fun checkTransaction(stx: SignedTransaction) = requireThat {
                val output = stx.tx.outputs.single().data
                "The output must be a CommercialPaperState" using (output is VotingrState)
            }
        }
        val txId = subFlow(signedTransactionFlow).id

        return subFlow(ReceiveFinalityFlow(counterpartySession, expectedTxId = txId))
    }
}