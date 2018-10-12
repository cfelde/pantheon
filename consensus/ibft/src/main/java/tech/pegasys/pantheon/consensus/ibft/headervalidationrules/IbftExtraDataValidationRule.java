package tech.pegasys.pantheon.consensus.ibft.headervalidationrules;

import static tech.pegasys.pantheon.consensus.ibft.IbftHelpers.calculateRequiredValidatorQuorum;

import tech.pegasys.pantheon.consensus.common.ValidatorProvider;
import tech.pegasys.pantheon.consensus.ibft.IbftBlockHashing;
import tech.pegasys.pantheon.consensus.ibft.IbftContext;
import tech.pegasys.pantheon.consensus.ibft.IbftExtraData;
import tech.pegasys.pantheon.ethereum.ProtocolContext;
import tech.pegasys.pantheon.ethereum.core.Address;
import tech.pegasys.pantheon.ethereum.core.BlockHeader;
import tech.pegasys.pantheon.ethereum.mainnet.AttachedBlockHeaderValidationRule;
import tech.pegasys.pantheon.ethereum.rlp.RLPException;

import java.util.Collection;
import java.util.List;

import com.google.common.collect.Iterables;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Ensures the byte content of the extraData field can be deserialised into an appropriate
 * structure, and that the structure created contains data matching expectations from preceding
 * blocks.
 */
public class IbftExtraDataValidationRule implements AttachedBlockHeaderValidationRule<IbftContext> {

  private static final Logger LOG = LogManager.getLogger();

  private final boolean validateCommitSeals;

  public IbftExtraDataValidationRule(final boolean validateCommitSeals) {
    this.validateCommitSeals = validateCommitSeals;
  }

  @Override
  public boolean validate(
      final BlockHeader header,
      final BlockHeader parent,
      final ProtocolContext<IbftContext> context) {
    return validateExtraData(header, context);
  }

  /**
   * Responsible for determining the validity of the extra data field. Ensures:
   *
   * <ul>
   *   <li>Bytes in the extra data field can be decoded as per IBFT specification
   *   <li>Proposer (derived from the proposerSeal) is a member of the validators
   *   <li>Committers (derived from committerSeals) are all members of the validators
   * </ul>
   *
   * @param header the block header containing the extraData to be validated.
   * @return True if the extraData successfully produces an IstanbulExtraData object, false
   *     otherwise
   */
  private boolean validateExtraData(
      final BlockHeader header, final ProtocolContext<IbftContext> context) {
    try {
      final ValidatorProvider validatorProvider = context.getConsensusState().getVoteTally();
      final IbftExtraData ibftExtraData = IbftExtraData.decode(header.getExtraData());

      final Address proposer = IbftBlockHashing.recoverProposerAddress(header, ibftExtraData);

      final Collection<Address> storedValidators = validatorProvider.getCurrentValidators();

      if (!storedValidators.contains(proposer)) {
        LOG.trace("Proposer sealing block is not a member of the validators.");
        return false;
      }

      if (validateCommitSeals) {
        final List<Address> committers =
            IbftBlockHashing.recoverCommitterAddresses(header, ibftExtraData);
        if (!validateCommitters(committers, storedValidators)) {
          return false;
        }
      }

      if (!Iterables.elementsEqual(ibftExtraData.getValidators(), storedValidators)) {
        LOG.trace(
            "Incorrect validators. Expected {} but got {}.",
            storedValidators,
            ibftExtraData.getValidators());
        return false;
      }

    } catch (final RLPException ex) {
      LOG.trace("ExtraData field was unable to be deserialised into an IBFT Struct.", ex);
      return false;
    } catch (final IllegalArgumentException ex) {
      LOG.trace("Failed to verify extra data", ex);
      return false;
    }

    return true;
  }

  private boolean validateCommitters(
      final Collection<Address> committers, final Collection<Address> storedValidators) {

    final int minimumSealsRequired = calculateRequiredValidatorQuorum(storedValidators.size());
    if (committers.size() < minimumSealsRequired) {
      LOG.trace(
          "Insufficient committers to seal block. (Required {}, received {})",
          minimumSealsRequired,
          committers.size());
      return false;
    }

    if (!storedValidators.containsAll(committers)) {
      LOG.trace("Not all committers are in the locally maintained validator list.");
      return false;
    }

    return true;
  }
}