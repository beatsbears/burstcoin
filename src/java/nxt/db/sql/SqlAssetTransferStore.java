package nxt.db.sql;

import nxt.AssetTransfer;
import nxt.db.NxtIterator;
import nxt.db.NxtKey;
import nxt.db.store.AssetTransferStore;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public abstract class SqlAssetTransferStore implements AssetTransferStore {

    private static final NxtKey.LongKeyFactory<AssetTransfer> transferDbKeyFactory = new DbKey.LongKeyFactory<AssetTransfer>("id") {

        @Override
        public NxtKey newKey(AssetTransfer assetTransfer) {
            return assetTransfer.dbKey;
        }
    };
    private final EntitySqlTable<AssetTransfer> assetTransferTable = new EntitySqlTable<AssetTransfer>("asset_transfer", transferDbKeyFactory) {

        @Override
        protected AssetTransfer load(Connection con, ResultSet rs) throws SQLException {
            return new SqlAssetTransfer(rs);
        }

        @Override
        protected void save(Connection con, AssetTransfer assetTransfer) throws SQLException {
            saveAssetTransfer(con, assetTransfer);
        }
    };

    private void saveAssetTransfer(Connection con, AssetTransfer assetTransfer) throws SQLException {
        try (PreparedStatement pstmt = con.prepareStatement("INSERT INTO asset_transfer (id, asset_id, "
                + "sender_id, recipient_id, quantity, timestamp, height) "
                + "VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            int i = 0;
            pstmt.setLong(++i, assetTransfer.getId());
            pstmt.setLong(++i, assetTransfer.getAssetId());
            pstmt.setLong(++i, assetTransfer.getSenderId());
            pstmt.setLong(++i, assetTransfer.getRecipientId());
            pstmt.setLong(++i, assetTransfer.getQuantityQNT());
            pstmt.setInt(++i, assetTransfer.getTimestamp());
            pstmt.setInt(++i, assetTransfer.getHeight());
            pstmt.executeUpdate();
        }
    }

    @Override
    public NxtKey.LongKeyFactory<AssetTransfer> getTransferDbKeyFactory() {
        return transferDbKeyFactory;
    }

    @Override
    public EntitySqlTable<AssetTransfer> getAssetTransferTable() {
        return assetTransferTable;
    }

    @Override
    public NxtIterator<AssetTransfer> getAssetTransfers(long assetId, int from, int to) {
        return assetTransferTable.getManyBy(new DbClause.LongClause("asset_id", assetId), from, to);
    }

    @Override
    public NxtIterator<AssetTransfer> getAccountAssetTransfers(long accountId, int from, int to) {
        Connection con = null;
        try {
            con = Db.getConnection();
            PreparedStatement pstmt = con.prepareStatement("SELECT * FROM asset_transfer WHERE sender_id = ?"
                    + " UNION ALL SELECT * FROM asset_transfer WHERE recipient_id = ? AND sender_id <> ? ORDER BY height DESC"
                    + DbUtils.limitsClause(from, to));
            int i = 0;
            pstmt.setLong(++i, accountId);
            pstmt.setLong(++i, accountId);
            pstmt.setLong(++i, accountId);
            DbUtils.setLimits(++i, pstmt, from, to);
            return assetTransferTable.getManyBy(con, pstmt, false);
        } catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }

    @Override
    public NxtIterator<AssetTransfer> getAccountAssetTransfers(long accountId, long assetId, int from, int to) {
        Connection con = null;
        try {
            con = Db.getConnection();
            PreparedStatement pstmt = con.prepareStatement("SELECT * FROM asset_transfer WHERE sender_id = ? AND asset_id = ?"
                    + " UNION ALL SELECT * FROM asset_transfer WHERE recipient_id = ? AND sender_id <> ? AND asset_id = ? ORDER BY height DESC"
                    + DbUtils.limitsClause(from, to));
            int i = 0;
            pstmt.setLong(++i, accountId);
            pstmt.setLong(++i, assetId);
            pstmt.setLong(++i, accountId);
            pstmt.setLong(++i, accountId);
            pstmt.setLong(++i, assetId);
            DbUtils.setLimits(++i, pstmt, from, to);
            return assetTransferTable.getManyBy(con, pstmt, false);
        } catch (SQLException e) {
            DbUtils.close(con);
            throw new RuntimeException(e.toString(), e);
        }
    }

    @Override
    public int getTransferCount(long assetId) {
        try (Connection con = Db.getConnection();
             PreparedStatement pstmt = con.prepareStatement("SELECT COUNT(*) FROM asset_transfer WHERE asset_id = ?")) {
            pstmt.setLong(1, assetId);
            try (ResultSet rs = pstmt.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new RuntimeException(e.toString(), e);
        }
    }

    private class SqlAssetTransfer extends AssetTransfer {

        private SqlAssetTransfer(ResultSet rs) throws SQLException {
            super(rs.getLong("id"),
                    transferDbKeyFactory.newKey(rs.getLong("id")),
                    rs.getLong("asset_id"),
                    rs.getInt("height"),
                    rs.getLong("sender_id"),
                    rs.getLong("recipient_id"),
                    rs.getLong("quantity"),
                    rs.getInt("timestamp")
            );
        }
    }


}