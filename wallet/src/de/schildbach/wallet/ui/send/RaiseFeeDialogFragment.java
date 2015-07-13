/*
 * Copyright 2015 the original author or authors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package de.schildbach.wallet.ui.send;

import static com.google.common.base.Preconditions.checkNotNull;

import javax.annotation.Nullable;

import org.bitcoinj.core.Coin;
import org.bitcoinj.core.Sha256Hash;
import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.TransactionOutput;
import org.bitcoinj.core.Wallet;
import org.bitcoinj.core.Wallet.SendRequest;
import org.bitcoinj.wallet.KeyChain.KeyPurpose;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import android.app.Activity;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.FragmentManager;
import android.content.DialogInterface;
import android.content.DialogInterface.OnClickListener;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import de.schildbach.wallet.Configuration;
import de.schildbach.wallet.Constants;
import de.schildbach.wallet.WalletApplication;
import de.schildbach.wallet.ui.AbstractWalletActivity;
import de.schildbach.wallet.ui.DialogBuilder;
import de.schildbach.wallet_test.R;

/**
 * @author Andreas Schildbach
 */
public class RaiseFeeDialogFragment extends DialogFragment
{
	private static final String FRAGMENT_TAG = RaiseFeeDialogFragment.class.getName();
	private static final String KEY_TRANSACTION = "transaction";
	private static final Coin FEE_RAISE = FeeCategory.PRIORITY.feePerKb.multiply(2);

	public static void show(final FragmentManager fm, final Transaction tx)
	{
		final DialogFragment newFragment = instance(tx);
		newFragment.show(fm, FRAGMENT_TAG);
	}

	private static RaiseFeeDialogFragment instance(final Transaction tx)
	{
		final RaiseFeeDialogFragment fragment = new RaiseFeeDialogFragment();

		final Bundle args = new Bundle();
		args.putSerializable(KEY_TRANSACTION, tx.getHash().getBytes());
		fragment.setArguments(args);

		return fragment;
	}

	private AbstractWalletActivity activity;
	private WalletApplication application;
	private Configuration config;
	private Wallet wallet;

	private HandlerThread backgroundThread;
	private Handler backgroundHandler;

	private Transaction transaction;

	private static final Logger log = LoggerFactory.getLogger(RaiseFeeDialogFragment.class);

	@Override
	public void onAttach(final Activity activity)
	{
		super.onAttach(activity);

		this.activity = (AbstractWalletActivity) activity;
		this.application = (WalletApplication) activity.getApplication();
		this.config = application.getConfiguration();
		this.wallet = application.getWallet();
	}

	@Override
	public void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		final Bundle args = getArguments();
		final byte[] txHash = (byte[]) args.getSerializable(KEY_TRANSACTION);
		transaction = checkNotNull(wallet.getTransaction(Sha256Hash.wrap(txHash)));

		backgroundThread = new HandlerThread("backgroundThread", Process.THREAD_PRIORITY_BACKGROUND);
		backgroundThread.start();
		backgroundHandler = new Handler(backgroundThread.getLooper());
	}

	@Override
	public Dialog onCreateDialog(final Bundle savedInstanceState)
	{
		final DialogBuilder builder = new DialogBuilder(activity);
		builder.setTitle("Raise fee (experimental)");
		builder.setMessage(String.format("Do you want to raise the fee of this payment by %s? It will increase the chance it will confirm.", config
				.getFormat().format(FEE_RAISE)));
		builder.setPositiveButton("Raise", new OnClickListener()
		{
			@Override
			public void onClick(final DialogInterface dialog, final int which)
			{
				handleRaiseFee();
			}
		});
		builder.setNegativeButton(R.string.button_cancel, null);
		builder.setCancelable(false);

		return builder.create();
	}

	@Override
	public void onDestroy()
	{
		backgroundThread.getLooper().quit();

		super.onDestroy();
	}

	private void handleRaiseFee()
	{
		final TransactionOutput outputToSpend = findSpendableOutput(wallet, transaction);
		// TODO null check
		final Transaction transactionToSend = new Transaction(Constants.NETWORK_PARAMETERS);
		transactionToSend.addInput(outputToSpend);
		transactionToSend.addOutput(outputToSpend.getValue().subtract(FEE_RAISE), wallet.freshAddress(KeyPurpose.CHANGE));
		transactionToSend.setPurpose(Transaction.Purpose.RAISE_FEE);
		final SendRequest sendRequest = SendRequest.forTx(transactionToSend);
		wallet.signTransaction(sendRequest);

		log.info("transaction to send: {}", transactionToSend);

		wallet.commitTx(transactionToSend);
		application.broadcastTransaction(transactionToSend);
	}

	public static boolean feeCanBeRaised(final Wallet wallet, final Transaction transaction)
	{
		if (transaction.getConfidence().getDepthInBlocks() > 0)
			return false;
		else if (findSpendableOutput(wallet, transaction) == null)
			return false;
		else
			return true;
	}

	private static @Nullable TransactionOutput findSpendableOutput(final Wallet wallet, final Transaction transaction)
	{
		for (final TransactionOutput output : transaction.getOutputs())
		{
			if (output.isMine(wallet) && output.isAvailableForSpending() && output.getValue().isGreaterThan(FEE_RAISE))
				return output;
		}

		return null;
	}
}
