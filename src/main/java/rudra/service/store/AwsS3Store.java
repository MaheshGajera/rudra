package rudra.service.store;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.GetObjectRequest;
import com.amazonaws.services.s3.model.ListObjectsRequest;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.s3.model.S3ObjectInputStream;
import com.amazonaws.services.s3.model.S3ObjectSummary;

import rudra.model.ResourceDTO;
import rudra.model.ResourceGroupDTO;
import rudra.service.ResourceStore;

@Service
public class AwsS3Store implements ResourceStore {

    private static final Logger logger = LoggerFactory.getLogger( AwsS3Store.class );

    private static final String DELIMITER = "/";

    private static final String AWS_S3_HOST = "https://s3.ap-south-1.amazonaws.com/";

    private static final String THUMBNAIL_GROUP = "Thumbnail";

    
    @Autowired
    private AmazonS3 amazonS3;

    @Override
    public List<String> loadClientNames() {

        List<String> buckets = new ArrayList<>();
        for ( Bucket bucket : amazonS3.listBuckets() ) {
            buckets.add( bucket.getName() );
        }

        return buckets;
    }

    @Override
    public List<String> loadMainResourceGroupNames(String bucketName) {
        ListObjectsRequest listObjectRequest = new ListObjectsRequest().withBucketName( bucketName )
            .withDelimiter( DELIMITER );

        ObjectListing objectListing = amazonS3.listObjects( listObjectRequest );

        List<String> names = new ArrayList<>();
        for ( String name : objectListing.getCommonPrefixes() ) {
            String finalName = name.replaceAll( DELIMITER, "" );
            if ( THUMBNAIL_GROUP.equals( finalName ) ) continue;

            names.add( finalName );
        }

        return names;
    }

    @Override
    public Collection<ResourceGroupDTO> loadSubResourceGroups(String bucketName, String parentGroupName) {
        ListObjectsRequest listObjectRequest = new ListObjectsRequest().withBucketName( bucketName )
            .withPrefix( parentGroupName );

        ObjectListing objectListing = amazonS3.listObjects( listObjectRequest );
        Map<String, ResourceGroupDTO> mediaGroupList = new HashMap<>();

        List<S3ObjectSummary> objectSummaries = objectListing.getObjectSummaries();
        objectSummaries.sort( Comparator.comparing( S3ObjectSummary::getLastModified ).reversed() );

        for ( S3ObjectSummary objectSummary : objectSummaries ) {
            String revisedKey = objectSummary.getKey().replace( parentGroupName + DELIMITER, "" );

            if ( revisedKey.isEmpty() )
                continue;

            if ( revisedKey.endsWith( DELIMITER ) ) {
                String subGroupName = revisedKey.replace( DELIMITER, "" );
                if ( !mediaGroupList.containsKey( subGroupName ) ) {
                    ResourceGroupDTO mediaGroup = new ResourceGroupDTO();
                    mediaGroup.setGroupName( subGroupName );
                    mediaGroup.setGroupPath( objectSummary.getKey() );
                    mediaGroup.setLastModified( objectSummary.getLastModified() );
                    mediaGroupList.put( subGroupName, mediaGroup );
                }
            }
        }

        for ( S3ObjectSummary objectSummary : objectSummaries ) {
            String revisedKey = objectSummary.getKey().replace( parentGroupName + DELIMITER, "" );
            if ( revisedKey.isEmpty() || revisedKey.endsWith( DELIMITER ) )
                continue;

            String names[] = revisedKey.split( DELIMITER );
            if ( names.length > 0 && mediaGroupList.containsKey( names[0] ) ) {
                String fileName = names.length > 1 ? names[1] : revisedKey;
                ResourceDTO mediaData = new ResourceDTO( fileName, objectSummary.getSize(),
                    buildResourceURI( bucketName, objectSummary.getKey() ) );
                mediaGroupList.get( names[0] ).addMediaData( mediaData );
            }
        }

        return mediaGroupList.values().stream()
            .sorted( Comparator.comparing( ResourceGroupDTO::getLastModified ).reversed() )
            .collect( Collectors.toList() );
    }

    @Override
    public ResourceGroupDTO loadResourceGroup(String bucketName, String groupName) {
        ListObjectsRequest listObjectRequest = new ListObjectsRequest().withBucketName( bucketName )
            .withPrefix( groupName );

        ObjectListing objectListing = amazonS3.listObjects( listObjectRequest );
        List<S3ObjectSummary> objectSummaries = objectListing.getObjectSummaries();
        objectSummaries.sort( Comparator.comparing( S3ObjectSummary::getLastModified ).reversed() );

        ResourceGroupDTO mediaGroup = new ResourceGroupDTO();
        mediaGroup.setGroupName( groupName );

        Set<String> thumbnails = loadThumbnails( bucketName );
        for ( S3ObjectSummary objectSummary : objectSummaries ) {
            String revisedKey = objectSummary.getKey().replace( groupName + DELIMITER, "" );
            if ( revisedKey.isEmpty() ) {
                mediaGroup.setGroupPath( objectSummary.getKey() );
                continue;
            }
            if ( revisedKey.endsWith( DELIMITER ) )
                continue;

            String thumbnail = thumbnails.contains( objectSummary.getETag() )
                ? buildThumbnailURI( bucketName, objectSummary.getETag() )
                : null;
            
            ResourceDTO mediaData = new ResourceDTO( revisedKey, objectSummary.getSize(),
                buildResourceURI( bucketName, objectSummary.getKey() ),
                thumbnail );

            mediaGroup.addMediaData( mediaData );
        }

        return mediaGroup;
    }

    private String buildThumbnailURI(String bucketName, String eTag) {
        return AWS_S3_HOST + bucketName + DELIMITER + THUMBNAIL_GROUP + DELIMITER + eTag + ".png";
    }

    private String buildResourceURI(String bucketName, String key) {
        return AWS_S3_HOST + bucketName + DELIMITER + key.replaceAll( " ", "+" );
    }

    @Override
    public void loadResource(OutputStream out, String bucketName, String resourceKey) throws IOException {
        GetObjectRequest rangeObjectRequest = new GetObjectRequest( bucketName, resourceKey );
        S3Object s3Object = amazonS3.getObject( rangeObjectRequest );
        S3ObjectInputStream s3ObjectIS = s3Object.getObjectContent();

        byte[] data = new byte[2048];
        int read = 0;
        while (( read = s3ObjectIS.read( data ) ) > 0) {
            out.write( data, 0, read );
            out.flush();
        }
        s3ObjectIS.close();
        s3Object.close();
    }
    
    @Override
    public Set<String> loadThumbnails( String bucketName ) {
        ListObjectsRequest listObjectRequest = new ListObjectsRequest().withBucketName( bucketName )
            .withPrefix( THUMBNAIL_GROUP );
        
        ObjectListing objectListing = amazonS3.listObjects( listObjectRequest );
        List<S3ObjectSummary> objectSummaries = objectListing.getObjectSummaries();
        objectSummaries.sort( Comparator.comparing( S3ObjectSummary::getLastModified ).reversed() );

        Set<String> thumbnails = new HashSet<>();
        for ( S3ObjectSummary objectSummary : objectSummaries ) {
            String revisedKey = objectSummary.getKey().replace( THUMBNAIL_GROUP + DELIMITER, "" );
            if ( revisedKey.isEmpty() || revisedKey.endsWith( DELIMITER ) ) continue;

            thumbnails.add( revisedKey.replace( ".png","" ) );
        }
        
        return thumbnails;
    }
}
